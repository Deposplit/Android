package com.deposplit.driving_adapters

import com.deposplit.driven_ports.ContactRepository
import com.deposplit.driven_ports.KeyConflictRepository
import com.deposplit.driven_ports.RetainedDepositRepository
import com.deposplit.driven_ports.SecretRepository
import com.deposplit.driven_ports.ShareMetadataRepository
import com.deposplit.driven_ports.ShareRelay
import com.deposplit.driven_ports.ShareRelayResolver
import com.deposplit.driven_ports.ShareRepository
import com.deposplit.driving_ports.ContactManagement
import com.deposplit.driving_ports.Identity
import com.deposplit.driving_ports.ShareManagement
import com.deposplit.shamir.combine
import com.deposplit.shamir.combineWithIntegrity
import com.deposplit.shamir.split
import com.deposplit.value_objects.CipherSuite
import com.deposplit.value_objects.Contact
import com.deposplit.value_objects.CustodyHeartbeatTuning
import com.deposplit.value_objects.HeldShare
import com.deposplit.value_objects.KeyConflict
import com.deposplit.value_objects.MimeType
import com.deposplit.value_objects.PayloadCanonical
import com.deposplit.value_objects.ReconstructionIntegrity
import com.deposplit.value_objects.ReconstructionResult
import com.deposplit.value_objects.RegenerateIdentityResult
import com.deposplit.value_objects.RetainedDepositBlob
import com.deposplit.value_objects.Role
import com.deposplit.value_objects.Secret
import com.deposplit.value_objects.SecretState
import com.deposplit.value_objects.ShareMetadata
import com.deposplit.value_objects.ShareRequest
import com.deposplit.value_objects.ShareRequestState
import com.deposplit.value_objects.ShareTransactionType
import com.deposplit.value_objects.SecretLimits
import com.deposplit.value_objects.SecretTooLargeException
import com.deposplit.value_objects.SignatureVerificationException
import com.deposplit.value_objects.VerificationLevel
import java.time.Duration
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
    private val retainedDepositRepository: RetainedDepositRepository,
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
        val contact = contactRepository.getByVerifyKey(req.senderKey) ?: return false
        val canon = PayloadCanonical.forOpen(
            req.secretId, req.transactionType, req.recipientKey, req.label, req.secretCreatedAt, req.shareId, req.ciphertext,
            req.k, req.n, req.mimeType,
        )
        return identity.verify(canon, req.senderSignature, contact.verifyKey)
    }

    private fun verifyRespond(req: ShareRequest): Boolean {
        val sig = req.recipientSignature ?: return false
        val contact = contactRepository.getByVerifyKey(req.recipientKey) ?: return false
        val approved = req.state == ShareRequestState.APPROVED
        val signedCiphertext = if (approved && req.transactionType == ShareTransactionType.RETRIEVAL) req.ciphertext else null
        val canon = PayloadCanonical.forRespond(req.id, approved, signedCiphertext)
        return identity.verify(canon, sig, contact.verifyKey)
    }

    // ── Sender flows ──────────────────────────────────────────────────────────

    override fun deposit(
        secret: ByteArray,
        label: String,
        contacts: List<Contact>,
        threshold: Int,
        mimeType: MimeType,
    ) {
        if (secret.size > SecretLimits.MAX_SECRET_BYTES) {
            throw SecretTooLargeException(secret.size, SecretLimits.MAX_SECRET_BYTES)
        }
        val shares = split(secret, contacts.size, threshold)
        val secretId = UUID.randomUUID()
        val createdAt = Instant.now()
        shares.zip(contacts).forEach { (share, contact) ->
            val ciphertext = encryption.encrypt(share, contact.encKey)
            val canon = PayloadCanonical.forOpen(secretId, ShareTransactionType.DEPOSIT, contact.verifyKey, label, createdAt, null, ciphertext, threshold, contacts.size, mimeType)
            val senderSignature = identity.sign(canon)
            val req = relayForContact(contact).openShareRequest(
                secretId, contact.verifyKey, label, createdAt, ShareTransactionType.DEPOSIT, null, ciphertext,
                k = threshold, n = contacts.size, mimeType = mimeType, senderSignature = senderSignature,
            )
            shareMetadataRepository.save(ShareMetadata(req.id, secretId, contact.id))
            // Retained until this holder's pickup is confirmed (relay-observed or
            // heartbeat-attested), then discarded. Safe to retain: this blob is encrypted to the
            // holder's X25519 key, so this device cannot decrypt it itself.
            runCatching {
                retainedDepositRepository.save(
                    RetainedDepositBlob(req.id, secretId, contact.id, label, createdAt, ciphertext, threshold, contacts.size, mimeType),
                )
            }
        }
        secretRepository.save(Secret(secretId, label, mimeType, threshold, contacts.size, createdAt, SecretState.ACTIVE))
    }

    override fun listSecrets(): List<Secret> = secretRepository.getAll()

    override fun syncDistributed() {
        val existingMetadata = shareMetadataRepository.getAll()
        allRelays().forEach { relay ->
            runCatching { relay.listShareRequests(Role.SENDER, ShareTransactionType.DEPOSIT) }.getOrDefault(emptyList())
                .forEach { req ->
                    if (req.state == ShareRequestState.WITHDRAWN) {
                        // Best-effort tombstone: the holder unilaterally stopped holding
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
                    val contact = contactRepository.getByVerifyKey(req.recipientKey) ?: return@forEach
                    val priorConfirmedAt = existingMetadata.find { it.id == req.id }?.lastConfirmedAt
                    if (req.state == ShareRequestState.APPROVED && isRetentionStillPending(req.id)) {
                        // First-observed pickup confirmation (relay-observed channel):
                        // a one-time transition, not "still approved therefore still fresh" — an
                        // unchanging Approved row on a later poll must not keep bumping
                        // freshness, or a long-dead holder would look perpetually confirmed. The
                        // retained blob's continued existence is exactly the "not yet confirmed
                        // by any channel" marker, so its presence is what gates the stamp.
                        shareMetadataRepository.save(ShareMetadata(req.id, req.secretId, contact.id, Instant.now()))
                        runCatching { retainedDepositRepository.delete(req.id) }
                    } else {
                        shareMetadataRepository.save(ShareMetadata(req.id, req.secretId, contact.id, priorConfirmedAt))
                    }
                }
            // A retrieve approval is also proof-of-custody. Polled here purely for that
            // freshness side effect; the functional read path for these rows is reconstruct()/
            // listSentRequests(), unchanged.
            runCatching { relay.listShareRequests(Role.SENDER, ShareTransactionType.RETRIEVAL, ShareRequestState.APPROVED) }
                .getOrDefault(emptyList())
                .forEach { req ->
                    val shareId = req.shareId ?: return@forEach
                    val meta = existingMetadata.find { it.id == shareId } ?: return@forEach
                    shareMetadataRepository.save(meta.copy(lastConfirmedAt = Instant.now()))
                }
        }
        reconcileDiscarding()
        processHeartbeats()
    }

    private fun isRetentionStillPending(depositId: UUID): Boolean =
        runCatching { retainedDepositRepository.getAll() }.getOrDefault(emptyList()).any { it.id == depositId }

    // For every DISCARDING Secret, checks whether each remaining holder's fanned-out removal
    // request has been approved; approved ones are cleaned up (relay row deleted, local
    // ShareMetadata removed). Once a DISCARDING secret has no ShareMetadata rows left, its
    // Secret record itself is removed — the ACTIVE/DISCARDING two-state lifecycle.
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

    // A holder is worth prioritizing for a fresh retrieval ask when the custody-freshness rule
    // that decides "still counts toward n_live" already trusts them: an unexpired
    // proof-of-custody and no standing opt-out. Recomputed here (not shared with the UI layer's
    // own FreshnessBucket, which serves display, not targeting) — a small, deliberate duplication
    // of a threshold check rather than restructuring already-shipped UI code.
    private fun isConfirmed(meta: ShareMetadata): Boolean {
        val contact = contactRepository.getById(meta.contactId) ?: return false
        if (contact.heartbeatOptedOutAt != null) return false
        val lastConfirmedAt = meta.lastConfirmedAt ?: return false
        return Duration.between(lastConfirmedAt, Instant.now()) <= CustodyHeartbeatTuning.lossThreshold
    }

    override fun requestAll(secretId: UUID) {
        val secret = secretRepository.getAll().find { it.id == secretId } ?: return
        val deposited = shareMetadataRepository.getAll().filter { it.secretId == secretId }
        val existing = allRelays().flatMap { relay ->
            runCatching { relay.listShareRequests(Role.SENDER, ShareTransactionType.RETRIEVAL) }.getOrDefault(emptyList())
        }
        // Fan out to the health-informed fresh set first; widen to everyone only when
        // there aren't enough confirmed holders to reach k. A retrieval request exists solely to
        // feed an eventual reconstruct(), so this targeting applies here rather than as a
        // separate method.
        val confirmed = deposited.filter(::isConfirmed)
        val targets = if (confirmed.size >= secret.k) confirmed else deposited
        for (meta in targets) {
            val contact = contactRepository.getById(meta.contactId) ?: continue
            // Matched on secretId plus the holder's key. Not the local shareId — a recovered
            // ShareMetadata's id is a freshly generated local UUID with no relay-row counterpart.
            // And not secretId alone — every holder of a secret shares it, so one standing row
            // would silence the whole fan-out. A holder who rotated keys since the row was opened
            // no longer matches, which is right: that row is unreachable under the new key anyway.
            val hasActive = existing.any {
                it.secretId == meta.secretId &&
                    it.recipientKey.contentEquals(contact.verifyKey) &&
                    (it.state == ShareRequestState.PENDING || it.state == ShareRequestState.APPROVED)
            }
            if (!hasActive) runCatching {
                val canon = PayloadCanonical.forOpen(meta.secretId, ShareTransactionType.RETRIEVAL, contact.verifyKey, secret.label, secret.secretCreatedAt, meta.id, null)
                val senderSignature = identity.sign(canon)
                relayForContact(contact).openShareRequest(
                    meta.secretId, contact.verifyKey, secret.label, secret.secretCreatedAt, ShareTransactionType.RETRIEVAL, meta.id, null,
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
        val canon = PayloadCanonical.forOpen(meta.secretId, type, contact.verifyKey, secret.label, secret.secretCreatedAt, shareId, null)
        val senderSignature = identity.sign(canon)
        return relayForContact(contact).openShareRequest(
            meta.secretId, contact.verifyKey, secret.label, secret.secretCreatedAt, type, shareId, null,
            senderSignature = senderSignature,
        )
    }

    // Pure read: collects and decrypts k approved retrieval shares, but never tears down
    // local ShareMetadata or relay rows. Use discardSecret for teardown — reconstruct is now a
    // *step* toward a possible re-split, not an implicit "I'm done with this" signal.
    override fun reconstruct(secretId: UUID): ReconstructionResult {
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
        // Each decrypted share is kept paired with its originating contact so an
        // excluded index (from combineWithIntegrity) reports back as a suspect contact, not a
        // meaningless array position.
        val contactIds = mutableListOf<UUID>()
        val decrypted = approved.map { (_, req) ->
            val contact = contacts.find { it.verifyKey.contentEquals(req.recipientKey) }
                ?: error("Contact not found for recipient key")
            contactIds.add(contact.id)
            encryption.decrypt(req.ciphertext!!, contact.encKey)
        }
        val result = combineWithIntegrity(decrypted, secret.k)
        val integrity = when {
            !result.hasIntegrityMargin -> ReconstructionIntegrity.NoMargin
            result.excludedIndices.isEmpty() -> ReconstructionIntegrity.Confirmed
            else -> ReconstructionIntegrity.ExcludedSuspects(result.excludedIndices.map { contactIds[it] }.toSet())
        }
        return ReconstructionResult(result.secret, integrity, secret.mimeType)
    }

    // Fans out a sender-initiated removal to every known holder of secretId and flips the Secret
    // to DISCARDING immediately, before any holder has responded.
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
    // waiting for relay confirmation.
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
                val senderContact = contactRepository.getByVerifyKey(req.senderKey) ?: continue
                // A deposit without valid k/n/mimeType can't happen against a conforming relay
                // (all three required by ShareRequestsService) — skip defensively rather than store
                // a share we can't later report thresholds for during recovery.
                val k = req.k ?: continue
                val n = req.n ?: continue
                val mimeType = req.mimeType ?: continue
                // Order is load-bearing: approving is what clears the relay's only copy of the
                // ciphertext, so it is the last step of pickup and never the first. Decrypting and
                // storing first means a failure leaves the row pending with the relay's copy
                // intact, and the next poll simply retries. The ciphertext is already in hand and
                // already authenticated — verifyOpen above covers it — so the approval response's
                // echoed copy adds nothing. runCatching isolates one bad row from the rest of the
                // poll; it must never span a half-completed pickup.
                runCatching {
                    var stored = shareRepository.getPlaintextShare(req.secretId) != null
                    if (!stored) {
                        req.ciphertext?.let { ct ->
                            val plaintext = encryption.decrypt(ct, senderContact.encKey)
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
                                    mimeType = mimeType,
                                )
                            )
                            stored = true
                        }
                    }
                    // Sent even when an earlier poll already stored the share but failed to
                    // acknowledge it: without the approval the sender never observes the pickup and
                    // keeps her retained blob forever.
                    if (stored) {
                        val canon = PayloadCanonical.forRespond(req.id, approved = true, ciphertext = null)
                        relay.respondToShareRequest(req.id, true, recipientSignature = identity.sign(canon))
                    }
                }
            }
        }
        processRecoveryMetadata()
        processRotations()
        emitHeartbeats()
    }

    // Holder side — opportunistically piggybacks this same inbox poll: for each distinct
    // sender this device currently holds at least one share from, pushes one coalesced heartbeat
    // (or opt-out notice) once the per-sender emission interval has elapsed. Each push is
    // independently best-effort so one unreachable BYOR relay doesn't block heartbeating other
    // senders. lastHeartbeatSentAt only advances on a *successful* push, so a transient failure
    // retries on the very next poll rather than waiting out the full interval again.
    private fun emitHeartbeats() {
        val held = shareRepository.getAll()
        val senderIds = held.map { it.contactId }.toSet()
        val now = Instant.now()
        for (contactId in senderIds) {
            val contact = contactRepository.getById(contactId) ?: continue
            val isDue = contact.lastHeartbeatSentAt?.let { Duration.between(it, now) >= CustodyHeartbeatTuning.emissionInterval } ?: true
            if (!isDue) continue
            val secretIds = if (contact.heartbeatEmissionOptedOut) emptyList() else held.filter { it.contactId == contactId }.map { it.secretId }
            val canon = PayloadCanonical.forHeartbeat(contact.verifyKey, secretIds, contact.heartbeatEmissionOptedOut)
            val signature = runCatching { identity.sign(canon) }.getOrNull() ?: continue
            val pushed = runCatching {
                relayForContact(contact).pushHeartbeat(contact.verifyKey, secretIds, contact.heartbeatEmissionOptedOut, signature)
            }.isSuccess
            if (pushed) {
                contactRepository.save(contact.copy(lastHeartbeatSentAt = now))
            }
        }
    }

    // Owner side — auto-verifies each holder's latest heartbeat (or opt-out notice)
    // against a known contact's trusted key, then updates local freshness/opt-out state. Never
    // deletes a heartbeat row — see CustodyHeartbeat for why it's a standing status, not a
    // one-shot delivery. Unknown senders and forged signatures are silently skipped, same
    // posture as processRotations().
    private fun processHeartbeats() {
        val myKey = identity.verifyKey()
        val existingMetadata = shareMetadataRepository.getAll()
        allRelays().forEach { relay ->
            val notices = runCatching { relay.listHeartbeats() }.getOrDefault(emptyList())
            for (notice in notices) {
                val contact = contactRepository.getByVerifyKey(notice.holderKey) ?: continue
                val canon = PayloadCanonical.forHeartbeat(myKey, notice.secretIds, notice.optedOut)
                if (!identity.verify(canon, notice.signature, notice.holderKey)) continue
                if (notice.optedOut) {
                    contactRepository.save(contact.copy(heartbeatOptedOutAt = notice.createdAt))
                    continue
                }
                if (contact.heartbeatOptedOutAt != null) {
                    contactRepository.save(contact.copy(heartbeatOptedOutAt = null))
                }
                for (secretId in notice.secretIds) {
                    val meta = existingMetadata.find { it.secretId == secretId && it.contactId == contact.id } ?: continue
                    shareMetadataRepository.save(meta.copy(lastConfirmedAt = notice.createdAt))
                    if (isRetentionStillPending(meta.id)) {
                        runCatching { retainedDepositRepository.delete(meta.id) }
                    }
                }
            }
        }
    }

    override fun setHeartbeatEmissionOptedOut(contactId: UUID, optedOut: Boolean) {
        val contact = contactRepository.getById(contactId) ?: error("Contact not found for id $contactId")
        // Reset so the changed preference reaches the contact on the very next poll rather than
        // waiting out the emission interval.
        contactRepository.save(contact.copy(heartbeatEmissionOptedOut = optedOut, lastHeartbeatSentAt = null))
    }

    // Receiving side — auto-verifies a signed rotation notice against the trusted old key
    // already on file for a known contact, downgrades the verification level to at most LOW
    // (a signed rotation proves continuity of key control, not a fresh personhood check, so it
    // can never carry a higher level forward), and updates the contact
    // record in place, preserving contactId. Unknown senders and forged/mismatched signatures are
    // silently skipped — a stranger's notice must never mutate a real contact.
    private fun processRotations() {
        allRelays().forEach { relay ->
            val notices = runCatching { relay.listRotations() }.getOrDefault(emptyList())
            for (notice in notices) {
                val contact = contactRepository.getByVerifyKey(notice.oldVerifyKey) ?: continue
                val canon = PayloadCanonical.forRotation(notice.recipientKey, notice.newVerifyKey, notice.newEncKey, notice.newCipherSuite)
                if (!identity.verify(canon, notice.signature, notice.oldVerifyKey)) continue
                // A rotation claiming continuity from a key the user has flagged
                // compromised is never auto-accepted. Capture a durable local KeyConflict record
                // *before* touching the relay notice: the relay may lose its state at any time and
                // must never be relied on to keep the alert alive. Skip updateContact entirely —
                // the contact record is left untouched; only a fresh human-verified relink can move
                // it forward.
                if (contact.revokedVerifyKeys.any { it.contentEquals(notice.oldVerifyKey) }) {
                    runCatching {
                        keyConflictRepository.save(
                            KeyConflict(
                                id = UUID.randomUUID(),
                                contactId = contact.id,
                                oldVerifyKey = notice.oldVerifyKey,
                                newVerifyKey = notice.newVerifyKey,
                                newEncKey = notice.newEncKey,
                                detectedAt = Instant.now(),
                            )
                        )
                    }
                    runCatching { relay.deleteRotation(notice.id) }
                    continue
                }
                // A cipher-suite-only change goes through the same downgrade as a plain
                // key rotation: an algorithm change is still continuity of key control, not a
                // fresh personhood check.
                val downgraded = minOf(contact.verificationLevel, VerificationLevel.LOW)
                runCatching {
                    contactManagement.updateContact(contact.id, notice.newVerifyKey, notice.newEncKey, notice.newCipherSuite, downgraded)
                }
                runCatching { relay.deleteRotation(notice.id) }
            }
        }
    }

    // Sending side (client primitive only — see ShareManagement.pushRotation). Signs the
    // new keys with the device's *current* identity, which becomes oldVerifyKey on the wire,
    // proving continuity of key control to the recipient.
    override fun pushRotation(contactId: UUID, newVerifyKey: ByteArray, newEncKey: ByteArray, newCipherSuite: CipherSuite) {
        val contact = contactRepository.getById(contactId) ?: error("Contact not found for id $contactId")
        val canon = PayloadCanonical.forRotation(contact.verifyKey, newVerifyKey, newEncKey, newCipherSuite)
        val signature = identity.sign(canon)
        relayForContact(contact).pushRotation(contact.verifyKey, newVerifyKey, newEncKey, newCipherSuite, signature)
    }

    // Whether every relay this device knows of answered. syncInbox and syncDistributed soft-fail
    // per relay on purpose — one dark BYOR relay must not blank out results from the others — which
    // also means neither can tell its caller that a relay went unheard. Rotation is the one caller
    // that needs to know, because it is about to retire the identity those rows are addressed to,
    // so it asks separately rather than the fan-out growing a return value every other caller
    // would ignore.
    private fun allRelaysAnswered(): Boolean = allRelays().all { relay ->
        runCatching {
            relay.listShareRequests(Role.RECIPIENT, ShareTransactionType.DEPOSIT, ShareRequestState.PENDING)
        }.isSuccess
    }

    // The identity-regeneration trigger. Order matters: the drain and rotation pushes must both
    // happen before activateKeyPair, since pushRotation (and the drain's own relay calls) sign
    // with whatever identity is currently persisted — that's what proves continuity from the old
    // key to each contact. If the app dies partway through, the old identity is still active
    // (nothing was persisted yet), so a retry simply regenerates and re-pushes from scratch; any
    // contact who received an orphaned first attempt auto-corrects on the next successful push,
    // per the existing K_old-signed auto-accept rule.
    override fun regenerateIdentity(): RegenerateIdentityResult {
        runCatching { syncInbox() }
        runCatching { syncDistributed() }
        val drainSucceeded = allRelaysAnswered()
        val newKeys = identity.generateNewKeyPair()
        val contacts = contactRepository.getAll()
        var notified = 0
        for (contact in contacts) {
            val success = runCatching { pushRotation(contact.id, newKeys.verifyKey, newKeys.encKey, CipherSuite.current) }.isSuccess
            if (success) notified++
        }
        identity.activateKeyPair(newKeys)
        return RegenerateIdentityResult(notified, contacts.size, drainSucceeded)
    }

    // Identity recovery — sender/owner side. Consumes pending recoveryMetadata pushes addressed
    // to this device, rebuilding Secret/ShareMetadata records from what each holder reports. A
    // push is trusted only once its senderSignature verifies against a *known* contact — the
    // holder must already have been re-added out-of-band before their push is honored. Consumed
    // rows are deleted from the relay once processed.
    private fun processRecoveryMetadata() {
        allRelays().forEach { relay ->
            val pushes = runCatching {
                relay.listShareRequests(Role.RECIPIENT, ShareTransactionType.INVENTORY, ShareRequestState.APPROVED)
            }.getOrDefault(emptyList())
            for (req in pushes.filter(::verifyOpen)) {
                val holderContact = contactRepository.getByVerifyKey(req.senderKey) ?: continue
                val k = req.k ?: continue
                val n = req.n ?: continue
                val mimeType = req.mimeType ?: continue
                if (secretRepository.getAll().none { it.id == req.secretId }) {
                    runCatching { secretRepository.save(Secret(req.secretId, req.label, mimeType, k, n, req.secretCreatedAt, SecretState.ACTIVE)) }
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
                    share.secretId, ShareTransactionType.INVENTORY, contact.verifyKey, share.label, share.createdAt, null, null,
                    share.k, share.n, share.mimeType,
                )
                val senderSignature = identity.sign(canon)
                relayForContact(contact).openShareRequest(
                    share.secretId, contact.verifyKey, share.label, share.createdAt, ShareTransactionType.INVENTORY, null, null,
                    k = share.k, n = share.n, mimeType = share.mimeType, senderSignature = senderSignature,
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
            // device once identities can be rebuilt independently after recovery.
            val plaintext = shareRepository.getPlaintextShare(request.secretId) ?: error("Share for secret ${request.secretId} not in local storage")
            // Re-encrypt to the requester's *current* X25519 key — looked up live, not pinned at
            // deposit time. This is what lets reconstruction survive a sender key rotation/
            // recovery — the core reason the holder decrypts at pickup.
            val requesterContact = contactRepository.getByVerifyKey(request.senderKey) ?: error("Contact not found for requester")
            encryption.encrypt(plaintext, requesterContact.encKey)
        } else null
        val canon = PayloadCanonical.forRespond(requestId, approved, ciphertext)
        val recipientSignature = identity.sign(canon)
        relay.respondToShareRequest(requestId, approved, ciphertext, recipientSignature)
        if (approved && request.transactionType == ShareTransactionType.REMOVAL) {
            shareRepository.getAll().firstOrNull { it.secretId == request.secretId }?.let { shareRepository.delete(it.id) }
        }
    }

    // Unilateral, no approval needed — but not purely silent: best-effort notifies
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
            runCatching { relayForContact(senderContact).withdrawShareRequests(senderKey = senderContact.verifyKey) }
        }
        shareRepository.getAll()
            .filter { it.contactId == contactId }
            .forEach { shareRepository.delete(it.id) }
    }

    // ── Key conflicts (never auto-resolved) ─────────────────────────────────────

    override fun listKeyConflicts(): List<KeyConflict> = keyConflictRepository.getAll()

    override fun dismissKeyConflict(id: UUID) = keyConflictRepository.delete(id)
}
