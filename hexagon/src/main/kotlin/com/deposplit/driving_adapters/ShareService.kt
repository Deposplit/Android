package com.deposplit.driving_adapters

import com.deposplit.driven_ports.ContactRepository
import com.deposplit.driven_ports.KeyConflictRepository
import com.deposplit.driven_ports.SecretRepository
import com.deposplit.driven_ports.ShareMetadataRepository
import com.deposplit.driven_ports.ShareRelay
import com.deposplit.driven_ports.ShareRelayResolver
import com.deposplit.driven_ports.ShareRepository
import com.deposplit.driving_ports.ContactManagement
import com.deposplit.driving_ports.Identity
import com.deposplit.driving_ports.ShareManagement
import com.deposplit.shamir.combine
import com.deposplit.shamir.split
import com.deposplit.value_objects.Contact
import com.deposplit.value_objects.HeldShare
import com.deposplit.value_objects.KeyConflict
import com.deposplit.value_objects.PayloadCanonical
import com.deposplit.value_objects.Role
import com.deposplit.value_objects.Secret
import com.deposplit.value_objects.SecretState
import com.deposplit.value_objects.ShareMetadata
import com.deposplit.value_objects.ShareRequest
import com.deposplit.value_objects.ShareRequestState
import com.deposplit.value_objects.ShareTransactionType
import com.deposplit.value_objects.SignatureVerificationException
import com.deposplit.value_objects.VerificationLevel
import java.time.Instant
import java.util.UUID

class ShareService(
    private val relayResolver: ShareRelayResolver,
    private val encryption: ShareEncryption,
    private val shareRepository: ShareRepository,
    private val shareMetadataRepository: ShareMetadataRepository,
    private val secretRepository: SecretRepository,
    private val contactRepository: ContactRepository,
    private val contactManagement: ContactManagement,
    private val keyConflictRepository: KeyConflictRepository,
    private val identity: Identity,
) : ShareManagement {

    // ── Relay resolution ────────────────────────────────────────────────────

    // Every distinct relay referenced across the contact list, plus the default — used by
    // fan-out methods (syncInbox, listPendingRequests, syncDistributed, listSentRequests) since a
    // device has no other way to know in advance which relay a given contact's pending item lives
    // on. Deduped by URL, not per-contact; each relay call is independently soft-failed so one
    // unreachable BYOR relay doesn't blank out results from the default relay or others.
    private fun allRelays(): List<ShareRelay> =
        (contactRepository.getAll().map { it.relayBaseUrl } + null).distinct().map(relayResolver::resolve)

    private fun relayForContact(contact: Contact): ShareRelay = relayResolver.resolve(contact.relayBaseUrl)

    // Finds a row by id across every known relay — the caller (UI) has no relay context for a
    // bare requestId, only the fan-out list already used to discover it. Returns the relay it was
    // found on too, so the caller can act on it through the *same* relay rather than re-resolving
    // (which could point elsewhere if a contact's relayBaseUrl changed since the row was created).
    private fun findShareRequest(requestId: UUID): Pair<ShareRelay, ShareRequest> =
        allRelays().firstNotNullOfOrNull { relay ->
            runCatching { relay to relay.getShareRequest(requestId) }.getOrNull()
        } ?: error("Share request $requestId not found on any known relay")

    // ── Signature helpers ────────────────────────────────────────────────────

    private fun verifyOpen(req: ShareRequest): Boolean {
        val contact = contactRepository.getByEdKey(req.senderKey) ?: return false
        val canon = PayloadCanonical.forOpen(
            req.secretId, req.transactionType, req.recipientKey, req.label, req.secretCreatedAt, req.shareId, req.ciphertext,
            req.k, req.n,
        )
        return identity.verify(canon, req.senderSignature, contact.edPublicKey)
    }

    private fun verifyRespond(req: ShareRequest): Boolean {
        val sig = req.recipientSignature ?: return false
        val contact = contactRepository.getByEdKey(req.recipientKey) ?: return false
        val approved = req.state == ShareRequestState.APPROVED
        val signedCiphertext = if (approved && req.transactionType == ShareTransactionType.RETRIEVAL) req.ciphertext else null
        val canon = PayloadCanonical.forRespond(req.id, approved, signedCiphertext)
        return identity.verify(canon, sig, contact.edPublicKey)
    }

    // ── Sender flows ──────────────────────────────────────────────────────────

    override fun deposit(secret: ByteArray, label: String, contacts: List<Contact>, threshold: Int) {
        val shares = split(secret, contacts.size, threshold)
        val secretId = UUID.randomUUID()
        val createdAt = Instant.now()
        shares.zip(contacts).forEach { (share, contact) ->
            val ciphertext = encryption.encrypt(share, contact.xPublicKey)
            val canon = PayloadCanonical.forOpen(secretId, ShareTransactionType.DEPOSIT, contact.edPublicKey, label, createdAt, null, ciphertext, threshold, contacts.size)
            val senderSignature = identity.sign(canon)
            val req = relayForContact(contact).openShareRequest(
                secretId, contact.edPublicKey, label, createdAt, ShareTransactionType.DEPOSIT, null, ciphertext,
                k = threshold, n = contacts.size, senderSignature = senderSignature,
            )
            shareMetadataRepository.save(ShareMetadata(req.id, secretId, contact.id))
        }
        secretRepository.save(Secret(secretId, label, threshold, contacts.size, createdAt, SecretState.ACTIVE))
    }

    override fun listSecrets(): List<Secret> = secretRepository.getAll()

    override fun syncDistributed() {
        allRelays().forEach { relay ->
            runCatching { relay.listShareRequests(Role.SENDER, ShareTransactionType.DEPOSIT) }.getOrDefault(emptyList())
                .forEach { req ->
                    if (req.state == ShareRequestState.WITHDRAWN) {
                        // Best-effort tombstone (item 9): the holder unilaterally stopped holding
                        // this share. Drop the local pointer so the health count reflects it,
                        // then clean up the relay row — it has served its purpose and needn't
                        // linger. Row *absence* is never itself a signal; only an *observed*
                        // withdrawn state counts, and we've just observed it.
                        runCatching { shareMetadataRepository.delete(req.id) }
                        runCatching { relay.deleteShareRequest(req.id) }
                        return@forEach
                    }
                    // A row for a holder we no longer have a contact record for can't be
                    // re-anchored to a contactId — skip rather than drop the holder's identity.
                    val contact = contactRepository.getByEdKey(req.recipientKey) ?: return@forEach
                    shareMetadataRepository.save(ShareMetadata(req.id, req.secretId, contact.id))
                }
        }
        reconcileDiscarding()
    }

    // For every DISCARDING Secret, checks whether each remaining holder's fanned-out removal
    // request has been approved; approved ones are cleaned up (relay row deleted, local
    // ShareMetadata removed). Once a DISCARDING secret has no ShareMetadata rows left, its
    // Secret record itself is removed. See item 11's two-state lifecycle.
    private fun reconcileDiscarding() {
        val discarding = secretRepository.getAll().filter { it.state == SecretState.DISCARDING }
        if (discarding.isEmpty()) return
        val discardingIds = discarding.map { it.id }.toSet()

        val removalRequests: List<Pair<ShareRelay, ShareRequest>> = allRelays().flatMap { relay ->
            runCatching { relay.listShareRequests(Role.SENDER, ShareTransactionType.REMOVAL) }.getOrDefault(emptyList())
                .filter { it.secretId in discardingIds }
                .map { relay to it }
        }

        for (secret in discarding) {
            val metasForSecret = shareMetadataRepository.getAll().filter { it.secretId == secret.id }
            for (meta in metasForSecret) {
                val approvedRemoval = removalRequests.firstOrNull { (_, r) -> r.shareId == meta.id && r.state == ShareRequestState.APPROVED }
                    ?: continue
                runCatching { approvedRemoval.first.deleteShareRequest(meta.id) }
                runCatching { shareMetadataRepository.delete(meta.id) }
            }
            val remaining = shareMetadataRepository.getAll().filter { it.secretId == secret.id }
            if (remaining.isEmpty()) {
                runCatching { secretRepository.delete(secret.id) }
            }
        }
    }

    override fun listDistributed(): List<ShareMetadata> = shareMetadataRepository.getAll()

    override fun listSentRequests(): List<ShareRequest> =
        allRelays()
            .flatMap { relay -> runCatching { relay.listShareRequests(Role.SENDER) }.getOrDefault(emptyList()) }
            .filterNot { it.transactionType == ShareTransactionType.DEPOSIT }

    override fun requestAll(secretId: UUID) {
        val secret = secretRepository.getAll().find { it.id == secretId } ?: return
        val deposited = shareMetadataRepository.getAll().filter { it.secretId == secretId }
        val existing = allRelays().flatMap { relay ->
            runCatching { relay.listShareRequests(Role.SENDER, ShareTransactionType.RETRIEVAL) }.getOrDefault(emptyList())
        }
        for (meta in deposited) {
            val contact = contactRepository.getById(meta.contactId) ?: continue
            // Matched on secretId, not the local shareId — a recovered ShareMetadata's id is a
            // freshly generated local UUID with no relay-row counterpart. See item 8.
            val hasActive = existing.any {
                it.secretId == meta.secretId &&
                    (it.state == ShareRequestState.PENDING || it.state == ShareRequestState.APPROVED)
            }
            if (!hasActive) runCatching {
                val canon = PayloadCanonical.forOpen(meta.secretId, ShareTransactionType.RETRIEVAL, contact.edPublicKey, secret.label, secret.secretCreatedAt, meta.id, null)
                val senderSignature = identity.sign(canon)
                relayForContact(contact).openShareRequest(
                    meta.secretId, contact.edPublicKey, secret.label, secret.secretCreatedAt, ShareTransactionType.RETRIEVAL, meta.id, null,
                    senderSignature = senderSignature,
                )
            }
        }
    }

    override fun openRequest(shareId: UUID, type: ShareTransactionType): ShareRequest {
        val meta = shareMetadataRepository.getAll().find { it.id == shareId }
            ?: error("No local share record for id $shareId")
        val secret = secretRepository.getAll().find { it.id == meta.secretId }
            ?: error("No local record for secret ${meta.secretId}")
        val contact = contactRepository.getById(meta.contactId)
            ?: error("Contact not found for id ${meta.contactId}")
        val canon = PayloadCanonical.forOpen(meta.secretId, type, contact.edPublicKey, secret.label, secret.secretCreatedAt, shareId, null)
        val senderSignature = identity.sign(canon)
        return relayForContact(contact).openShareRequest(
            meta.secretId, contact.edPublicKey, secret.label, secret.secretCreatedAt, type, shareId, null,
            senderSignature = senderSignature,
        )
    }

    // Pure read (item 11): collects and decrypts k approved retrieval shares, but never tears down
    // local ShareMetadata or relay rows. Use discardSecret for teardown — reconstruct is now a
    // *step* toward a possible re-split, not an implicit "I'm done with this" signal.
    override fun reconstruct(secretId: UUID): ByteArray {
        val secret = secretRepository.getAll().find { it.id == secretId }
            ?: error("No local record for secret $secretId")
        val allRequests: List<Pair<ShareRelay, ShareRequest>> = allRelays().flatMap { relay ->
            runCatching { relay.listShareRequests(Role.SENDER, ShareTransactionType.RETRIEVAL) }.getOrDefault(emptyList())
                .map { relay to it }
        }
        // An unverified recipientSignature is treated as "not yet approved" rather than a hard
        // error — a forged approval simply doesn't count toward the threshold.
        val approved = allRequests.filter { (_, r) ->
            r.secretId == secretId &&
                r.state == ShareRequestState.APPROVED &&
                r.ciphertext != null &&
                verifyRespond(r)
        }
        check(approved.size >= secret.k) { "Need at least ${secret.k} approved shares (have ${approved.size})" }
        val contacts = contactRepository.getAll()
        val decrypted = approved.map { (_, req) ->
            val contact = contacts.find { it.edPublicKey.contentEquals(req.recipientKey) }
                ?: error("Contact not found for recipient key")
            encryption.decrypt(req.ciphertext!!, contact.xPublicKey)
        }
        return combine(decrypted)
    }

    // Fans out a sender-initiated removal to every known holder of secretId and flips the Secret
    // to DISCARDING immediately, before any holder has responded — see item 11.
    override fun discardSecret(secretId: UUID) {
        val secret = secretRepository.getAll().find { it.id == secretId }
            ?: error("No local record for secret $secretId")
        secretRepository.save(secret.copy(state = SecretState.DISCARDING))
        shareMetadataRepository.getAll().filter { it.secretId == secretId }.forEach { share ->
            runCatching { openRequest(share.id, ShareTransactionType.REMOVAL) }
        }
    }

    // Local-only teardown for a DISCARDING secret whose holders won't all respond (e.g. a
    // permanently dark holder) — removes the Secret and its remaining ShareMetadata rows without
    // waiting for relay confirmation. See item 11.
    override fun forceForgetSecret(secretId: UUID) {
        shareMetadataRepository.getAll().filter { it.secretId == secretId }.forEach { share ->
            runCatching { shareMetadataRepository.delete(share.id) }
        }
        secretRepository.delete(secretId)
    }

    // ── Recipient flows ───────────────────────────────────────────────────────

    override fun syncInbox() {
        allRelays().forEach { relay ->
            val pending = runCatching {
                relay.listShareRequests(Role.RECIPIENT, ShareTransactionType.DEPOSIT, ShareRequestState.PENDING)
            }.getOrDefault(emptyList())
            // Unknown sender or unverified senderSignature: skip silently, do not auto-approve.
            for (req in pending.filter(::verifyOpen)) {
                val senderContact = contactRepository.getByEdKey(req.senderKey) ?: continue
                // A deposit without valid k/n can't happen against a conforming relay (required by
                // ShareRequestsService) — skip defensively rather than store a share we can't
                // later report thresholds for during recovery.
                val k = req.k ?: continue
                val n = req.n ?: continue
                if (shareRepository.getPlaintextShare(req.secretId) == null) {
                    runCatching {
                        val canon = PayloadCanonical.forRespond(req.id, approved = true, ciphertext = null)
                        val recipientSignature = identity.sign(canon)
                        val responded = relay.respondToShareRequest(req.id, true, recipientSignature = recipientSignature)
                        responded.ciphertext?.let { ct ->
                            val plaintext = encryption.decrypt(ct, senderContact.xPublicKey)
                            shareRepository.save(
                                HeldShare(
                                    id = req.id,
                                    secretId = req.secretId,
                                    label = req.label,
                                    contactId = senderContact.id,
                                    senderPseudonym = senderContact.pseudonym,
                                    createdAt = req.secretCreatedAt,
                                    pickedUpAt = Instant.now(),
                                    plaintextShare = plaintext,
                                    k = k,
                                    n = n,
                                )
                            )
                        }
                    }
                }
            }
        }
        processRecoveryMetadata()
        processRotations()
    }

    // Item 9, receiving side — auto-verifies a signed rotation notice against the trusted old key
    // already on file for a known contact, downgrades the verification level to at most LOW per
    // item 10's unifying rule (a signed rotation proves continuity of key control, not a fresh
    // personhood check, so it can never carry a higher level forward), and updates the contact
    // record in place, preserving contactId. Unknown senders and forged/mismatched signatures are
    // silently skipped — a stranger's notice must never mutate a real contact.
    private fun processRotations() {
        allRelays().forEach { relay ->
            val notices = runCatching { relay.listRotations() }.getOrDefault(emptyList())
            for (notice in notices) {
                val contact = contactRepository.getByEdKey(notice.oldEd25519Key) ?: continue
                val canon = PayloadCanonical.forRotation(notice.recipientKey, notice.newEd25519Key, notice.newX25519Key)
                if (!identity.verify(canon, notice.signature, notice.oldEd25519Key)) continue
                // Item 10 — a rotation claiming continuity from a key the user has flagged
                // compromised is never auto-accepted. Capture a durable local KeyConflict record
                // *before* touching the relay notice: the relay may lose its state at any time and
                // must never be relied on to keep the alert alive. Skip updateContact entirely —
                // the contact record is left untouched; only a fresh human-verified relink can move
                // it forward.
                if (contact.revokedEdKeys.any { it.contentEquals(notice.oldEd25519Key) }) {
                    runCatching {
                        keyConflictRepository.save(
                            KeyConflict(
                                id = UUID.randomUUID(),
                                contactId = contact.id,
                                oldEd25519Key = notice.oldEd25519Key,
                                newEd25519Key = notice.newEd25519Key,
                                newX25519Key = notice.newX25519Key,
                                detectedAt = Instant.now(),
                            )
                        )
                    }
                    runCatching { relay.deleteRotation(notice.id) }
                    continue
                }
                val downgraded = minOf(contact.verificationLevel, VerificationLevel.LOW)
                runCatching {
                    contactManagement.updateContact(contact.id, notice.newEd25519Key, notice.newX25519Key, downgraded)
                }
                runCatching { relay.deleteRotation(notice.id) }
            }
        }
    }

    // Item 9, sending side (client primitive only — see ShareManagement.pushRotation). Signs the
    // new keys with the device's *current* identity, which becomes oldEd25519Key on the wire,
    // proving continuity of key control to the recipient.
    override fun pushRotation(contactId: UUID, newEd25519Key: ByteArray, newX25519Key: ByteArray) {
        val contact = contactRepository.getById(contactId) ?: error("Contact not found for id $contactId")
        val canon = PayloadCanonical.forRotation(contact.edPublicKey, newEd25519Key, newX25519Key)
        val signature = identity.sign(canon)
        relayForContact(contact).pushRotation(contact.edPublicKey, newEd25519Key, newX25519Key, signature)
    }

    // Identity recovery (item 8) — sender/owner side. Consumes pending recoveryMetadata pushes
    // addressed to this device, rebuilding Secret/ShareMetadata records from what each holder
    // reports. A push is trusted only once its senderSignature verifies against a *known* contact
    // — the holder must already have been re-added out-of-band (item 8 step 1) before their push
    // is honored. Consumed rows are deleted from the relay once processed.
    private fun processRecoveryMetadata() {
        allRelays().forEach { relay ->
            val pushes = runCatching {
                relay.listShareRequests(Role.RECIPIENT, ShareTransactionType.INVENTORY, ShareRequestState.APPROVED)
            }.getOrDefault(emptyList())
            for (req in pushes.filter(::verifyOpen)) {
                val holderContact = contactRepository.getByEdKey(req.senderKey) ?: continue
                val k = req.k ?: continue
                val n = req.n ?: continue
                if (secretRepository.getAll().none { it.id == req.secretId }) {
                    runCatching { secretRepository.save(Secret(req.secretId, req.label, k, n, req.secretCreatedAt, SecretState.ACTIVE)) }
                }
                if (shareMetadataRepository.getAll().none { it.secretId == req.secretId && it.contactId == holderContact.id }) {
                    runCatching { shareMetadataRepository.save(ShareMetadata(UUID.randomUUID(), req.secretId, holderContact.id)) }
                }
                runCatching { relay.deleteShareRequest(req.id) }
            }
        }
    }

    override fun pushRecoveryMetadata(contactId: UUID) {
        val contact = contactRepository.getById(contactId) ?: error("Contact not found for id $contactId")
        shareRepository.getAll().filter { it.contactId == contactId }.forEach { share ->
            runCatching {
                val canon = PayloadCanonical.forOpen(
                    share.secretId, ShareTransactionType.INVENTORY, contact.edPublicKey, share.label, share.createdAt, null, null,
                    share.k, share.n,
                )
                val senderSignature = identity.sign(canon)
                relayForContact(contact).openShareRequest(
                    share.secretId, contact.edPublicKey, share.label, share.createdAt, ShareTransactionType.INVENTORY, null, null,
                    k = share.k, n = share.n, senderSignature = senderSignature,
                )
            }
        }
    }

    override fun listHeld(): List<HeldShare> = shareRepository.getAll()

    override fun listPendingRequests(): List<ShareRequest> =
        allRelays()
            .flatMap { relay ->
                runCatching { relay.listShareRequests(Role.RECIPIENT, state = ShareRequestState.PENDING) }.getOrDefault(emptyList())
            }
            .filterNot { it.transactionType == ShareTransactionType.DEPOSIT }
            // A forged removal/retrieval request has no AEAD backstop — must never reach the UI.
            .filter(::verifyOpen)

    override fun respond(requestId: UUID, approved: Boolean) {
        val (relay, request) = findShareRequest(requestId)
        if (!verifyOpen(request)) {
            throw SignatureVerificationException("senderSignature does not verify for request $requestId")
        }
        val ciphertext = if (approved && request.transactionType == ShareTransactionType.RETRIEVAL) {
            // Matched on secretId, not the sender's local shareId — that id is meaningless to this
            // device once identities can be rebuilt independently after recovery (item 8).
            val plaintext = shareRepository.getPlaintextShare(request.secretId) ?: error("Share for secret ${request.secretId} not in local storage")
            // Re-encrypt to the requester's *current* X25519 key — looked up live, not pinned at
            // deposit time. This is what lets reconstruction survive a sender key rotation/
            // recovery (item 7's core reason for existing).
            val requesterContact = contactRepository.getByEdKey(request.senderKey) ?: error("Contact not found for requester")
            encryption.encrypt(plaintext, requesterContact.xPublicKey)
        } else null
        val canon = PayloadCanonical.forRespond(requestId, approved, ciphertext)
        val recipientSignature = identity.sign(canon)
        relay.respondToShareRequest(requestId, approved, ciphertext, recipientSignature)
        if (approved && request.transactionType == ShareTransactionType.REMOVAL) {
            shareRepository.getAll().firstOrNull { it.secretId == request.secretId }?.let { shareRepository.delete(it.id) }
        }
    }

    // Unilateral, no approval needed — but as of item 9 not purely silent: best-effort notifies
    // the sender via a withdraw tombstone before the local record is dropped. The relay call is
    // fire-and-forget; local deletion always proceeds regardless of its outcome.
    override fun deleteHeldShare(shareId: UUID) {
        shareRepository.getAll().find { it.id == shareId }?.let { share ->
            contactRepository.getById(share.contactId)?.let { senderContact ->
                runCatching { relayForContact(senderContact).withdrawShareRequests(secretId = share.secretId) }
            }
        }
        shareRepository.delete(shareId)
    }

    // Same best-effort withdraw-tombstone courtesy as deleteHeldShare, but scoped to every share
    // from contactId in one relay call (senderKey) rather than one per secretId.
    override fun deleteAllHeldFromSender(contactId: UUID) {
        contactRepository.getById(contactId)?.let { senderContact ->
            runCatching { relayForContact(senderContact).withdrawShareRequests(senderKey = senderContact.edPublicKey) }
        }
        shareRepository.getAll()
            .filter { it.contactId == contactId }
            .forEach { shareRepository.delete(it.id) }
    }

    // ── Item 10: key conflicts (never auto-resolved) ────────────────────────────

    override fun listKeyConflicts(): List<KeyConflict> = keyConflictRepository.getAll()

    override fun dismissKeyConflict(id: UUID) = keyConflictRepository.delete(id)
}
