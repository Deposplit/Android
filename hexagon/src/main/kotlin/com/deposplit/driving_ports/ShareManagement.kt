package com.deposplit.driving_ports

import com.deposplit.value_objects.CipherSuite
import com.deposplit.value_objects.Contact
import com.deposplit.value_objects.HeldShare
import com.deposplit.value_objects.KeyConflict
import com.deposplit.value_objects.ReconstructionResult
import com.deposplit.value_objects.RegenerateIdentityResult
import com.deposplit.value_objects.Secret
import com.deposplit.value_objects.ShareMetadata
import com.deposplit.value_objects.ShareRequest
import com.deposplit.value_objects.ShareTransactionType
import java.util.UUID

interface ShareManagement {
    // ─── Sender ───────────────────────────────────────────────────────────────
    fun deposit(secret: ByteArray, label: String, contacts: List<Contact>, threshold: Int)
    fun listSecrets(): List<Secret>
    fun syncDistributed()
    fun listDistributed(): List<ShareMetadata>
    fun listSentRequests(): List<ShareRequest>
    fun requestAll(secretId: UUID)
    fun openRequest(shareId: UUID, type: ShareTransactionType): ShareRequest
    // Pure read — collects approved retrieval shares (possibly more than k) and decrypts them.
    // Never tears down local ShareMetadata or relay rows; use discardSecret for that.
    // Cross-checks any surplus beyond k for consistency — throws rather than returning a guessed
    // secret if the surplus can't be reconciled.
    fun reconstruct(secretId: UUID): ReconstructionResult
    // Fans out a sender-initiated removal request to every known holder of secretId and flips the
    // Secret to DISCARDING immediately (before any holder responds).
    fun discardSecret(secretId: UUID)
    // Local-only teardown for a DISCARDING secret whose holders will never all respond (e.g. a
    // permanently dark holder). Does not wait for or require relay confirmation.
    fun forceForgetSecret(secretId: UUID)

    // ─── Recipient ────────────────────────────────────────────────────────────
    fun syncInbox()
    fun listHeld(): List<HeldShare>
    fun listPendingRequests(): List<ShareRequest>
    fun respond(requestId: UUID, approved: Boolean)
    fun deleteHeldShare(shareId: UUID)
    fun deleteAllHeldFromSender(contactId: UUID)

    // ─── Identity recovery — holder side ─────────────────────────────────────
    // Pushes a metadata-only report (no share bytes) for every HeldShare held from contactId back
    // to that contact, so a recovering owner can rebuild her ShareMetadata/Secret records. Call
    // after ContactManagement.updateContact has relinked the re-presented identity to the
    // existing contact.
    fun pushRecoveryMetadata(contactId: UUID)

    // ─── Signed rotate(K_old -> K_new) push, client primitive only ──────────────
    // Signs newVerifyKey/newEncKey with the device's *current* identity (which becomes
    // oldVerifyKey on the wire) and pushes one signed notice to contactId. Reused unchanged by
    // regenerateIdentity(), the "regenerate my own identity" trigger. newCipherSuite is the
    // signing + key-agreement algorithm pairing newVerifyKey/newEncKey use.
    fun pushRotation(contactId: UUID, newVerifyKey: ByteArray, newEncKey: ByteArray, newCipherSuite: CipherSuite)

    // ─── Key conflicts (never auto-resolved), local-only, no relay involvement ─────────────────
    fun listKeyConflicts(): List<KeyConflict>
    fun dismissKeyConflict(id: UUID)

    // ─── The holder role. This device's own choice to stop (or resume) heartbeating
    // contactId (who is the owner in that relationship). Updates the local preference only — the
    // opportunistic syncInbox() emission loop is what actually reaches the contact, on its
    // normal per-sender cadence; this resets that contact's lastHeartbeatSentAt so the change
    // reaches them on the very next poll rather than waiting out the interval.
    fun setHeartbeatEmissionOptedOut(contactId: UUID, optedOut: Boolean)

    // ─── The "regenerate my own identity" trigger (proactive rotation while still holding the
    // device and old keys — distinct from device-loss identity recovery). Best-effort
    // drains the inbox/distributed state under the old identity, generates a fresh keypair, pushes
    // a signed rotation notice (via the existing pushRotation, unchanged) to every contact while
    // still signing as the old identity, then activates the new keypair locally. A contact whose
    // push fails is not retried — same one-shot semantics as pushRotation itself.
    fun regenerateIdentity(): RegenerateIdentityResult
}
