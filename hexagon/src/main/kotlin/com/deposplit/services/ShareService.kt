package com.deposplit.services

import com.deposplit.driven_ports.ContactRepository
import com.deposplit.driven_ports.ShareMetadataRepository
import com.deposplit.driven_ports.ShareRelay
import com.deposplit.driven_ports.ShareRepository
import com.deposplit.driving_ports.ShareManagement
import com.deposplit.shamir.combine
import com.deposplit.shamir.split
import com.deposplit.value_objects.Contact
import com.deposplit.value_objects.HeldShare
import com.deposplit.value_objects.Role
import com.deposplit.value_objects.ShareMetadata
import com.deposplit.value_objects.ShareRequest
import com.deposplit.value_objects.ShareRequestState
import com.deposplit.value_objects.ShareRequestType
import java.time.Instant
import java.util.UUID

class ShareService(
    private val relay: ShareRelay,
    private val encryption: ShareEncryption,
    private val shareRepository: ShareRepository,
    private val shareMetadataRepository: ShareMetadataRepository,
    private val contactRepository: ContactRepository,
) : ShareManagement {

    // ── Sender flows ──────────────────────────────────────────────────────────

    override fun deposit(secret: ByteArray, label: String, contacts: List<Contact>, threshold: Int) {
        val shares = split(secret, contacts.size, threshold)
        val secretId = UUID.randomUUID()
        val createdAt = Instant.now()
        shares.zip(contacts).forEach { (share, contact) ->
            val ciphertext = encryption.encrypt(share, contact.xPublicKey)
            val req = relay.openShareRequest(secretId, contact.edPublicKey, label, createdAt, ShareRequestType.PICK_UP, null, ciphertext)
            shareMetadataRepository.save(ShareMetadata(req.id, secretId, label, contact.edPublicKey, createdAt))
        }
    }

    override fun syncDistributed() {
        relay.listShareRequests(Role.SENDER, ShareRequestType.PICK_UP).forEach { req ->
            shareMetadataRepository.save(ShareMetadata(req.id, req.secretId, req.label, req.recipientKey, req.secretCreatedAt))
        }
    }

    override fun listDistributed(): List<ShareMetadata> = shareMetadataRepository.getAll()

    override fun listSentRequests(): List<ShareRequest> =
        relay.listShareRequests(Role.SENDER).filterNot { it.requestType == ShareRequestType.PICK_UP }

    override fun requestAll(secretId: UUID) {
        val deposited = shareMetadataRepository.getAll().filter { it.secretId == secretId }
        val existing = relay.listShareRequests(Role.SENDER, ShareRequestType.RETRIEVE)
        for (meta in deposited) {
            val hasActive = existing.any {
                it.shareId == meta.id &&
                    (it.state == ShareRequestState.PENDING || it.state == ShareRequestState.APPROVED)
            }
            if (!hasActive) runCatching {
                relay.openShareRequest(meta.secretId, meta.recipientKey, meta.label, meta.secretCreatedAt, ShareRequestType.RETRIEVE, meta.id, null)
            }
        }
    }

    override fun openRequest(shareId: UUID, type: ShareRequestType): ShareRequest {
        val meta = shareMetadataRepository.getAll().find { it.id == shareId }
            ?: error("No local share record for id $shareId")
        return relay.openShareRequest(meta.secretId, meta.recipientKey, meta.label, meta.secretCreatedAt, type, shareId, null)
    }

    override fun reconstruct(secretId: UUID): ByteArray {
        val allRequests = relay.listShareRequests(Role.SENDER, ShareRequestType.RETRIEVE)
        val approved = allRequests.filter {
            it.secretId == secretId &&
                it.state == ShareRequestState.APPROVED &&
                it.ciphertext != null
        }
        check(approved.size >= 2) { "Need at least 2 approved shares (have ${approved.size})" }
        val contacts = contactRepository.getAll()
        val decrypted = approved.map { req ->
            val contact = contacts.find { it.edPublicKey.contentEquals(req.recipientKey) }
                ?: error("Contact not found for recipient key")
            encryption.decrypt(req.ciphertext!!, contact.xPublicKey)
        }
        val secretBytes = combine(decrypted)
        for (req in approved) {
            req.shareId?.let { pickUpId ->
                runCatching { relay.deleteShareRequest(pickUpId) }
                runCatching { shareMetadataRepository.delete(pickUpId) }
            }
        }
        return secretBytes
    }

    // ── Recipient flows ───────────────────────────────────────────────────────

    override fun syncInbox() {
        val pending = relay.listShareRequests(Role.RECIPIENT, ShareRequestType.PICK_UP, ShareRequestState.PENDING)
        for (req in pending) {
            if (shareRepository.getCiphertext(req.id) == null) {
                runCatching {
                    val responded = relay.respondToShareRequest(req.id, true)
                    responded.ciphertext?.let { ct ->
                        shareRepository.save(
                            HeldShare(
                                id = req.id,
                                secretId = req.secretId,
                                label = req.label,
                                senderKey = req.senderKey,
                                createdAt = req.secretCreatedAt,
                                pickedUpAt = Instant.now(),
                                ciphertext = ct,
                            )
                        )
                    }
                }
            }
        }
    }

    override fun listHeld(): List<HeldShare> = shareRepository.getAll()

    override fun listPendingRequests(): List<ShareRequest> =
        relay.listShareRequests(Role.RECIPIENT, state = ShareRequestState.PENDING)
            .filterNot { it.requestType == ShareRequestType.PICK_UP }

    override fun respond(requestId: UUID, approved: Boolean) {
        val request = relay.getShareRequest(requestId)
        val ciphertext = if (approved && request.requestType == ShareRequestType.RETRIEVE) {
            val pickUpId = request.shareId ?: error("Retrieve request $requestId has no shareId")
            shareRepository.getCiphertext(pickUpId) ?: error("Share $pickUpId not in local storage")
        } else null
        relay.respondToShareRequest(requestId, approved, ciphertext)
        if (approved && request.requestType == ShareRequestType.DELETE) {
            request.shareId?.let { shareRepository.delete(it) }
        }
    }

    override fun deleteHeldShare(shareId: UUID) = shareRepository.delete(shareId)

    override fun deleteAllHeldFromSender(senderKey: ByteArray) {
        shareRepository.getAll()
            .filter { it.senderKey.contentEquals(senderKey) }
            .forEach { shareRepository.delete(it.id) }
    }
}
