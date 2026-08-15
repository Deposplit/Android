package com.deposplit.driving_adapters

import com.deposplit.driven_ports.ContactRepository
import com.deposplit.driven_ports.SecretRepository
import com.deposplit.driven_ports.ShareMetadataRepository
import com.deposplit.driven_ports.ShareRelay
import com.deposplit.driven_ports.ShareRelayResolver
import com.deposplit.driven_ports.ShareRepository
import com.deposplit.driving_ports.Identity
import com.deposplit.driving_ports.ShareManagement
import com.deposplit.shamir.combine
import com.deposplit.shamir.split
import com.deposplit.value_objects.Contact
import com.deposplit.value_objects.HeldShare
import com.deposplit.value_objects.PayloadCanonical
import com.deposplit.value_objects.Role
import com.deposplit.value_objects.Secret
import com.deposplit.value_objects.SecretState
import com.deposplit.value_objects.ShareMetadata
import com.deposplit.value_objects.ShareRequest
import com.deposplit.value_objects.ShareRequestState
import com.deposplit.value_objects.ShareRequestType
import com.deposplit.value_objects.SignatureVerificationException
import java.time.Instant
import java.util.UUID

class ShareService(
    private val relayResolver: ShareRelayResolver,
    private val encryption: ShareEncryption,
    private val shareRepository: ShareRepository,
    private val shareMetadataRepository: ShareMetadataRepository,
    private val secretRepository: SecretRepository,
    private val contactRepository: ContactRepository,
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
            req.secretId, req.requestType, req.recipientKey, req.label, req.secretCreatedAt, req.shareId, req.ciphertext,
        )
        return identity.verify(canon, req.senderSignature, contact.edPublicKey)
    }

    private fun verifyRespond(req: ShareRequest): Boolean {
        val sig = req.recipientSignature ?: return false
        val contact = contactRepository.getByEdKey(req.recipientKey) ?: return false
        val approved = req.state == ShareRequestState.APPROVED
        val signedCiphertext = if (approved && req.requestType == ShareRequestType.RETRIEVE) req.ciphertext else null
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
            val canon = PayloadCanonical.forOpen(secretId, ShareRequestType.PICK_UP, contact.edPublicKey, label, createdAt, null, ciphertext)
            val senderSignature = identity.sign(canon)
            val req = relayForContact(contact).openShareRequest(
                secretId, contact.edPublicKey, label, createdAt, ShareRequestType.PICK_UP, null, ciphertext, senderSignature,
            )
            shareMetadataRepository.save(ShareMetadata(req.id, secretId, contact.id))
        }
        secretRepository.save(Secret(secretId, label, threshold, contacts.size, createdAt, SecretState.ACTIVE))
    }

    override fun listSecrets(): List<Secret> = secretRepository.getAll()

    override fun syncDistributed() {
        allRelays().forEach { relay ->
            runCatching { relay.listShareRequests(Role.SENDER, ShareRequestType.PICK_UP) }.getOrDefault(emptyList())
                .forEach { req ->
                    // A row for a holder we no longer have a contact record for can't be
                    // re-anchored to a contactId — skip rather than drop the holder's identity.
                    val contact = contactRepository.getByEdKey(req.recipientKey) ?: return@forEach
                    shareMetadataRepository.save(ShareMetadata(req.id, req.secretId, contact.id))
                }
        }
        reconcileDiscarding()
    }

    // For every DISCARDING Secret, checks whether each remaining holder's fanned-out delete
    // request has been approved; approved ones are cleaned up (relay row deleted, local
    // ShareMetadata removed). Once a DISCARDING secret has no ShareMetadata rows left, its
    // Secret record itself is removed. See item 11's two-state lifecycle.
    private fun reconcileDiscarding() {
        val discarding = secretRepository.getAll().filter { it.state == SecretState.DISCARDING }
        if (discarding.isEmpty()) return
        val discardingIds = discarding.map { it.id }.toSet()

        val deleteRequests: List<Pair<ShareRelay, ShareRequest>> = allRelays().flatMap { relay ->
            runCatching { relay.listShareRequests(Role.SENDER, ShareRequestType.DELETE) }.getOrDefault(emptyList())
                .filter { it.secretId in discardingIds }
                .map { relay to it }
        }

        for (secret in discarding) {
            val metasForSecret = shareMetadataRepository.getAll().filter { it.secretId == secret.id }
            for (meta in metasForSecret) {
                val approvedDelete = deleteRequests.firstOrNull { (_, r) -> r.shareId == meta.id && r.state == ShareRequestState.APPROVED }
                    ?: continue
                runCatching { approvedDelete.first.deleteShareRequest(meta.id) }
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
            .filterNot { it.requestType == ShareRequestType.PICK_UP }

    override fun requestAll(secretId: UUID) {
        val secret = secretRepository.getAll().find { it.id == secretId } ?: return
        val deposited = shareMetadataRepository.getAll().filter { it.secretId == secretId }
        val existing = allRelays().flatMap { relay ->
            runCatching { relay.listShareRequests(Role.SENDER, ShareRequestType.RETRIEVE) }.getOrDefault(emptyList())
        }
        for (meta in deposited) {
            val contact = contactRepository.getById(meta.contactId) ?: continue
            val hasActive = existing.any {
                it.shareId == meta.id &&
                    (it.state == ShareRequestState.PENDING || it.state == ShareRequestState.APPROVED)
            }
            if (!hasActive) runCatching {
                val canon = PayloadCanonical.forOpen(meta.secretId, ShareRequestType.RETRIEVE, contact.edPublicKey, secret.label, secret.secretCreatedAt, meta.id, null)
                val senderSignature = identity.sign(canon)
                relayForContact(contact).openShareRequest(
                    meta.secretId, contact.edPublicKey, secret.label, secret.secretCreatedAt, ShareRequestType.RETRIEVE, meta.id, null, senderSignature,
                )
            }
        }
    }

    override fun openRequest(shareId: UUID, type: ShareRequestType): ShareRequest {
        val meta = shareMetadataRepository.getAll().find { it.id == shareId }
            ?: error("No local share record for id $shareId")
        val secret = secretRepository.getAll().find { it.id == meta.secretId }
            ?: error("No local record for secret ${meta.secretId}")
        val contact = contactRepository.getById(meta.contactId)
            ?: error("Contact not found for id ${meta.contactId}")
        val canon = PayloadCanonical.forOpen(meta.secretId, type, contact.edPublicKey, secret.label, secret.secretCreatedAt, shareId, null)
        val senderSignature = identity.sign(canon)
        return relayForContact(contact).openShareRequest(
            meta.secretId, contact.edPublicKey, secret.label, secret.secretCreatedAt, type, shareId, null, senderSignature,
        )
    }

    // Pure read (item 11): collects and decrypts k approved retrieve shares, but never tears down
    // local ShareMetadata or relay rows. Use discardSecret for teardown — reconstruct is now a
    // *step* toward a possible re-split, not an implicit "I'm done with this" signal.
    override fun reconstruct(secretId: UUID): ByteArray {
        val secret = secretRepository.getAll().find { it.id == secretId }
            ?: error("No local record for secret $secretId")
        val allRequests: List<Pair<ShareRelay, ShareRequest>> = allRelays().flatMap { relay ->
            runCatching { relay.listShareRequests(Role.SENDER, ShareRequestType.RETRIEVE) }.getOrDefault(emptyList())
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

    // Fans out a sender-initiated delete to every known holder of secretId and flips the Secret
    // to DISCARDING immediately, before any holder has responded — see item 11.
    override fun discardSecret(secretId: UUID) {
        val secret = secretRepository.getAll().find { it.id == secretId }
            ?: error("No local record for secret $secretId")
        secretRepository.save(secret.copy(state = SecretState.DISCARDING))
        shareMetadataRepository.getAll().filter { it.secretId == secretId }.forEach { share ->
            runCatching { openRequest(share.id, ShareRequestType.DELETE) }
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
                relay.listShareRequests(Role.RECIPIENT, ShareRequestType.PICK_UP, ShareRequestState.PENDING)
            }.getOrDefault(emptyList())
            // Unknown sender or unverified senderSignature: skip silently, do not auto-approve.
            for (req in pending.filter(::verifyOpen)) {
                val senderContact = contactRepository.getByEdKey(req.senderKey) ?: continue
                if (shareRepository.getPlaintextShare(req.id) == null) {
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
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    override fun listHeld(): List<HeldShare> = shareRepository.getAll()

    override fun listPendingRequests(): List<ShareRequest> =
        allRelays()
            .flatMap { relay ->
                runCatching { relay.listShareRequests(Role.RECIPIENT, state = ShareRequestState.PENDING) }.getOrDefault(emptyList())
            }
            .filterNot { it.requestType == ShareRequestType.PICK_UP }
            // A forged delete/retrieve request has no AEAD backstop — must never reach the UI.
            .filter(::verifyOpen)

    override fun respond(requestId: UUID, approved: Boolean) {
        val (relay, request) = findShareRequest(requestId)
        if (!verifyOpen(request)) {
            throw SignatureVerificationException("senderSignature does not verify for request $requestId")
        }
        val ciphertext = if (approved && request.requestType == ShareRequestType.RETRIEVE) {
            val pickUpId = request.shareId ?: error("Retrieve request $requestId has no shareId")
            val plaintext = shareRepository.getPlaintextShare(pickUpId) ?: error("Share $pickUpId not in local storage")
            // Re-encrypt to the requester's *current* X25519 key — looked up live, not pinned at
            // deposit time. This is what lets reconstruction survive a sender key rotation/
            // recovery (item 7's core reason for existing).
            val requesterContact = contactRepository.getByEdKey(request.senderKey) ?: error("Contact not found for requester")
            encryption.encrypt(plaintext, requesterContact.xPublicKey)
        } else null
        val canon = PayloadCanonical.forRespond(requestId, approved, ciphertext)
        val recipientSignature = identity.sign(canon)
        relay.respondToShareRequest(requestId, approved, ciphertext, recipientSignature)
        if (approved && request.requestType == ShareRequestType.DELETE) {
            request.shareId?.let { shareRepository.delete(it) }
        }
    }

    override fun deleteHeldShare(shareId: UUID) = shareRepository.delete(shareId)

    override fun deleteAllHeldFromSender(contactId: UUID) {
        shareRepository.getAll()
            .filter { it.contactId == contactId }
            .forEach { shareRepository.delete(it.id) }
    }
}
