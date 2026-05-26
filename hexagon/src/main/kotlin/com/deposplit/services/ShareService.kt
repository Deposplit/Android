package com.deposplit.services

import com.deposplit.driven_ports.ContactRepository
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
import java.util.UUID

class ShareService(
    private val relay: ShareRelay,
    private val encryption: ShareEncryption,
    private val shareRepository: ShareRepository,
    private val contactRepository: ContactRepository,
) : ShareManagement {

    override fun deposit(secret: ByteArray, label: String, contacts: List<Contact>, threshold: Int) {
        val shares = split(secret, contacts.size, threshold)
        val secretId = UUID.randomUUID()
        shares.zip(contacts).forEach { (share, contact) ->
            val ciphertext = encryption.encrypt(share, contact.xPublicKey)
            relay.depositShare(secretId, label, contact.edPublicKey, ciphertext)
        }
    }

    override fun listDistributed(): List<ShareMetadata> = relay.listShares(Role.SENDER)

    override fun listSentRequests(): List<ShareRequest> = relay.listShareRequests(Role.SENDER)

    override fun requestAll(secretId: UUID) {
        val distributed = relay.listShares(Role.SENDER).filter { it.secretId == secretId }
        val existing = relay.listShareRequests(Role.SENDER)
        for (share in distributed) {
            val hasActive = existing.any {
                it.share.id == share.id &&
                    it.requestType == ShareRequestType.RETRIEVE &&
                    (it.state == ShareRequestState.PENDING || it.state == ShareRequestState.APPROVED)
            }
            if (!hasActive) runCatching { relay.openShareRequest(share.id, ShareRequestType.RETRIEVE) }
        }
    }

    override fun openRequest(shareId: UUID, type: ShareRequestType): ShareRequest =
        relay.openShareRequest(shareId, type)

    override fun reconstruct(secretId: UUID): ByteArray {
        val allRequests = relay.listShareRequests(Role.SENDER)
        val approved = allRequests.filter {
            it.share.secretId == secretId &&
                it.requestType == ShareRequestType.RETRIEVE &&
                it.state == ShareRequestState.APPROVED &&
                it.ciphertext != null
        }
        check(approved.size >= 2) { "Need at least 2 approved shares (have ${approved.size})" }
        val contacts = contactRepository.getAll()
        val decrypted = approved.map { req ->
            val contact = contacts.find { it.edPublicKey.contentEquals(req.share.recipientKey) }
                ?: error("Contact not found for recipient key")
            encryption.decrypt(req.ciphertext!!, contact.xPublicKey)
        }
        val secretBytes = combine(decrypted)
        for (req in approved) runCatching { relay.deleteShare(req.share.id) }
        return secretBytes
    }

    override fun syncInbox() {
        val inbox = relay.listShares(Role.RECIPIENT)
        for (meta in inbox) {
            if (shareRepository.getCiphertext(meta.id) == null) {
                runCatching {
                    val ciphertext = relay.pickUpShare(meta.id)
                    shareRepository.save(
                        HeldShare(
                            id = meta.id,
                            secretId = meta.secretId,
                            label = meta.label,
                            senderKey = meta.senderKey,
                            createdAt = meta.createdAt,
                            ciphertext = ciphertext,
                        )
                    )
                }
            }
        }
    }

    override fun listHeld(): List<HeldShare> = shareRepository.getAll()

    override fun listPendingRequests(): List<ShareRequest> =
        relay.listShareRequests(Role.RECIPIENT, ShareRequestState.PENDING)

    override fun respond(requestId: UUID, approved: Boolean) {
        val request = relay.getShareRequest(requestId)
        val ciphertext = if (approved && request.requestType == ShareRequestType.RETRIEVE) {
            shareRepository.getCiphertext(request.share.id)
                ?: error("Share ciphertext not found in local storage")
        } else null
        relay.respondToShareRequest(requestId, approved, ciphertext)
        if (approved && request.requestType == ShareRequestType.DELETE) {
            shareRepository.delete(request.share.id)
        }
    }

    override fun deleteHeldShare(shareId: UUID) = shareRepository.delete(shareId)

    override fun deleteAllHeldFromSender(senderKey: ByteArray) {
        shareRepository.getAll()
            .filter { it.senderKey.contentEquals(senderKey) }
            .forEach { shareRepository.delete(it.id) }
    }
}
