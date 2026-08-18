package com.deposplit.driving_adapters

import com.deposplit.driven_ports.ContactRepository
import com.deposplit.driven_ports.IdentityStore
import com.deposplit.driven_ports.KeyConflictRepository
import com.deposplit.driven_ports.SecretRepository
import com.deposplit.driven_ports.ShareMetadataRepository
import com.deposplit.driven_ports.ShareRelay
import com.deposplit.driven_ports.ShareRelayResolver
import com.deposplit.driven_ports.ShareRepository
import com.deposplit.value_objects.Contact
import com.deposplit.value_objects.HeldShare
import com.deposplit.value_objects.KeyConflict
import com.deposplit.value_objects.KeyRotation
import com.deposplit.value_objects.PayloadCanonical
import com.deposplit.value_objects.Role
import com.deposplit.value_objects.Secret
import com.deposplit.value_objects.ShareMetadata
import com.deposplit.value_objects.ShareRequest
import com.deposplit.value_objects.ShareRequestState
import com.deposplit.value_objects.ShareTransactionType
import com.deposplit.value_objects.SignatureVerificationException
import com.deposplit.value_objects.VerificationLevel
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A keypair not tied to any Identity instance — used to sign fixture rows "as" a third party
 * (a known contact, or a stranger), independent of the ShareService under test's own identity.
 */
private class TestKeyPair(val publicKey: ByteArray, private val privateKey: ByteArray) {
    fun sign(bytes: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKey, 0))
        signer.update(bytes, 0, bytes.size)
        return signer.generateSignature()
    }

    companion object {
        fun generate(): TestKeyPair {
            val gen = Ed25519KeyPairGenerator()
            gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
            val pair = gen.generateKeyPair()
            return TestKeyPair(
                (pair.public as Ed25519PublicKeyParameters).encoded,
                (pair.private as Ed25519PrivateKeyParameters).encoded,
            )
        }
    }
}

private class InMemoryIdentityStoreForShareServiceTest : IdentityStore {
    private var _pseudonym = ""
    private var edPk = ByteArray(0)
    private var edSk = ByteArray(0)
    private var xPk = ByteArray(0)
    private var xSk = ByteArray(0)
    private var registered = false

    override fun isRegistered() = registered
    override fun save(pseudonym: String, edPk: ByteArray, edSk: ByteArray, xPk: ByteArray, xSk: ByteArray) {
        this._pseudonym = pseudonym
        this.edPk = edPk
        this.edSk = edSk
        this.xPk = xPk
        this.xSk = xSk
        registered = true
    }
    override fun pseudonym() = _pseudonym
    override fun edPublicKey() = edPk
    override fun edPrivateKey() = edSk
    override fun xPublicKey() = xPk
    override fun xPrivateKey() = xSk
}

/** A genuinely mutable in-memory store (not no-ops) — item 9's rotation-processing tests need to
 * observe the effect of ContactService.updateContact on the same contacts ShareService reads.
 */
private class FakeContactRepository(initial: List<Contact>) : ContactRepository {
    private val contacts = initial.toMutableList()
    override fun getAll() = contacts.toList()
    override fun getByEdKey(edPublicKey: ByteArray) = contacts.find { it.edPublicKey.contentEquals(edPublicKey) }
    override fun getById(id: UUID) = contacts.find { it.id == id }
    override fun save(contact: Contact) {
        contacts.removeAll { it.id == contact.id }
        contacts.add(contact)
    }
    override fun delete(contactId: UUID) { contacts.removeAll { it.id == contactId } }
}

private class FakeShareRepository : ShareRepository {
    private val shares = mutableListOf<HeldShare>()
    override fun getAll() = shares.toList()
    override fun getPlaintextShare(secretId: UUID) = shares.find { it.secretId == secretId }?.plaintextShare
    override fun save(share: HeldShare) { shares.add(share) }
    override fun delete(shareId: UUID) { shares.removeAll { it.id == shareId } }
}

private class FakeShareMetadataRepository : ShareMetadataRepository {
    private val metas = mutableListOf<ShareMetadata>()
    override fun getAll() = metas.toList()
    override fun save(share: ShareMetadata) { metas.add(share) }
    override fun delete(shareId: UUID) { metas.removeAll { it.id == shareId } }
}

private class FakeSecretRepository : SecretRepository {
    private val secrets = mutableListOf<Secret>()
    override fun getAll() = secrets.toList()
    override fun save(secret: Secret) {
        secrets.removeAll { it.id == secret.id }
        secrets.add(secret)
    }
    override fun delete(secretId: UUID) { secrets.removeAll { it.id == secretId } }
}

private class FakeKeyConflictRepository : KeyConflictRepository {
    private val conflicts = mutableListOf<KeyConflict>()
    override fun getAll() = conflicts.toList()
    override fun save(conflict: KeyConflict) { conflicts.add(conflict) }
    override fun delete(id: UUID) { conflicts.removeAll { it.id == id } }
}

private object NoOpShareEncryption : ShareEncryption {
    override fun encrypt(plaintext: ByteArray, recipientXPublicKey: ByteArray) = plaintext
    override fun decrypt(noncePlusCiphertext: ByteArray, recipientXPublicKey: ByteArray) = noncePlusCiphertext
}

/** In-memory ShareRelay test double. listShareRequests filters by transactionType/state (role is
 * ignored — every fixture row here is already addressed correctly) since syncInbox now issues two
 * differently-filtered queries per relay (deposit/pending, then inventory/approved) that
 * must not see each other's rows.
 */
private class FakeShareRelay(var unreachable: Boolean = false) : ShareRelay {
    data class OpenedRequest(val secretId: UUID, val recipientKey: ByteArray, val transactionType: ShareTransactionType, val k: Int?, val n: Int?)

    var pending: List<ShareRequest> = emptyList()
    var byId: MutableMap<UUID, ShareRequest> = mutableMapOf()
    val respondCalls = mutableListOf<UUID>()
    val deletedRequestIds = mutableListOf<UUID>()
    val openedRequests = mutableListOf<OpenedRequest>()

    // Item 9
    data class WithdrawCall(val senderKey: ByteArray?, val secretId: UUID?)
    data class PushedRotation(val recipientKey: ByteArray, val newEd25519Key: ByteArray, val newX25519Key: ByteArray, val signature: ByteArray)
    val withdrawCalls = mutableListOf<WithdrawCall>()
    val pushedRotations = mutableListOf<PushedRotation>()
    var rotationsToReturn: List<KeyRotation> = emptyList()
    val deletedRotationIds = mutableListOf<UUID>()
    var throwOnWithdraw = false

    override fun openShareRequest(
        secretId: UUID, recipientKey: ByteArray, label: String, secretCreatedAt: Instant,
        transactionType: ShareTransactionType, shareId: UUID?, ciphertext: ByteArray?, k: Int?, n: Int?, senderSignature: ByteArray,
    ): ShareRequest {
        openedRequests.add(OpenedRequest(secretId, recipientKey, transactionType, k, n))
        val selfApproved = transactionType == ShareTransactionType.INVENTORY
        val now = Instant.now()
        return ShareRequest(
            id = UUID.randomUUID(), secretId = secretId, senderKey = ByteArray(0), recipientKey = recipientKey,
            label = label, secretCreatedAt = secretCreatedAt, transactionType = transactionType,
            state = if (selfApproved) ShareRequestState.APPROVED else ShareRequestState.PENDING,
            shareId = shareId, requestedAt = now, respondedAt = if (selfApproved) now else null,
            ciphertext = null, k = k, n = n, senderSignature = senderSignature, recipientSignature = null,
        )
    }

    override fun listShareRequests(role: Role, transactionType: ShareTransactionType?, state: ShareRequestState?): List<ShareRequest> {
        if (unreachable) throw RuntimeException("simulated relay outage")
        return pending.filter { (transactionType == null || it.transactionType == transactionType) && (state == null || it.state == state) }
    }

    override fun getShareRequest(requestId: UUID): ShareRequest = byId.getValue(requestId)

    override fun respondToShareRequest(requestId: UUID, approved: Boolean, ciphertext: ByteArray?, recipientSignature: ByteArray): ShareRequest {
        respondCalls.add(requestId)
        val updated = byId.getValue(requestId).copy(state = if (approved) ShareRequestState.APPROVED else ShareRequestState.DENIED)
        byId[requestId] = updated
        return updated
    }

    override fun deleteShareRequest(requestId: UUID) { deletedRequestIds.add(requestId) }
    override fun deleteShareRequests(senderKey: ByteArray?, secretId: UUID?) {}

    override fun withdrawShareRequests(senderKey: ByteArray?, secretId: UUID?) {
        withdrawCalls.add(WithdrawCall(senderKey, secretId))
        if (throwOnWithdraw) throw RuntimeException("simulated withdraw failure")
    }

    override fun pushRotation(recipientKey: ByteArray, newEd25519Key: ByteArray, newX25519Key: ByteArray, signature: ByteArray) {
        pushedRotations.add(PushedRotation(recipientKey, newEd25519Key, newX25519Key, signature))
    }

    override fun listRotations(): List<KeyRotation> {
        if (unreachable) throw RuntimeException("simulated relay outage")
        return rotationsToReturn
    }

    override fun deleteRotation(id: UUID) { deletedRotationIds.add(id) }
}

/** Resolves to the same relay regardless of the requested URL — these tests exercise signature
 * verification, not multi-relay routing (see ShareRelayResolverFanOutTest for that).
 */
private class FixedShareRelayResolver(private val relay: ShareRelay) : ShareRelayResolver {
    override fun resolve(relayBaseUrl: String?): ShareRelay = relay
}

/** Covers the recipient-side signature-verification gating described in deposplit.com/CLAUDE.md's
 * BYOR section: syncInbox/listPendingRequests must drop rows with an unverifiable senderSignature
 * (unknown sender, or a genuine contact's key but a forged/mismatched signature) instead of
 * trusting whatever the relay returns, and respond must reject explicitly.
 */
class ShareServiceTest {

    private val aliceKeys = TestKeyPair.generate()
    private val strangerKeys = TestKeyPair.generate()

    private val aliceContact = Contact(
        id = UUID.randomUUID(),
        pseudonym = "alice",
        edPublicKey = aliceKeys.publicKey,
        xPublicKey = ByteArray(32) { 0x01 },
        verificationLevel = VerificationLevel.VERY_HIGH,
        verifiedAt = null,
        addedAt = Instant.now(),
    )

    private data class ShareServiceFixture(
        val svc: ShareService,
        val bob: IdentityService,
        val shareRepo: FakeShareRepository,
        val contactRepo: FakeContactRepository,
        val metaRepo: FakeShareMetadataRepository,
        val conflictRepo: FakeKeyConflictRepository,
    )

    private fun newService(relay: FakeShareRelay, contacts: List<Contact> = listOf(aliceContact)): ShareServiceFixture {
        val bobIdentity = IdentityService(InMemoryIdentityStoreForShareServiceTest())
        bobIdentity.register("bob")
        val shareRepo = FakeShareRepository()
        val contactRepo = FakeContactRepository(contacts)
        val metaRepo = FakeShareMetadataRepository()
        val conflictRepo = FakeKeyConflictRepository()
        val svc = ShareService(
            relayResolver = FixedShareRelayResolver(relay),
            encryption = NoOpShareEncryption,
            shareRepository = shareRepo,
            shareMetadataRepository = metaRepo,
            secretRepository = FakeSecretRepository(),
            contactRepository = contactRepo,
            contactManagement = ContactService(contactRepo),
            keyConflictRepository = conflictRepo,
            identity = bobIdentity,
        )
        return ShareServiceFixture(svc, bobIdentity, shareRepo, contactRepo, metaRepo, conflictRepo)
    }

    private fun depositRow(id: UUID, senderKey: ByteArray, recipientKey: ByteArray, senderSignature: ByteArray, ciphertext: ByteArray = byteArrayOf(1, 2, 3)): ShareRequest =
        ShareRequest(
            id = id,
            secretId = UUID.randomUUID(),
            senderKey = senderKey,
            recipientKey = recipientKey,
            label = "test secret",
            secretCreatedAt = Instant.now(),
            transactionType = ShareTransactionType.DEPOSIT,
            state = ShareRequestState.PENDING,
            shareId = null,
            requestedAt = Instant.now(),
            respondedAt = null,
            ciphertext = ciphertext,
            k = 2,
            n = 3,
            senderSignature = senderSignature,
            recipientSignature = null,
        )

    private fun signOpenAs(signer: TestKeyPair, row: ShareRequest): ByteArray =
        signer.sign(PayloadCanonical.forOpen(row.secretId, row.transactionType, row.recipientKey, row.label, row.secretCreatedAt, row.shareId, row.ciphertext, row.k, row.n))

    @Test
    fun `syncInbox approves and saves a Deposit with a valid senderSignature from a known contact`() {
        val relay = FakeShareRelay()
        val (svc, bob, shareRepo, _, _, _) = newService(relay)
        val id = UUID.randomUUID()
        val unsigned = depositRow(id, aliceKeys.publicKey, bob.edPublicKey(), ByteArray(0))
        val row = unsigned.copy(senderSignature = signOpenAs(aliceKeys, unsigned))
        relay.pending = listOf(row)
        relay.byId[id] = row

        svc.syncInbox()

        assertEquals(listOf(id), relay.respondCalls)
        assertEquals(listOf(id), shareRepo.getAll().map { it.id })
    }

    @Test
    fun `syncInbox skips a Deposit whose senderSignature doesn't verify against the claimed sender`() {
        val relay = FakeShareRelay()
        val (svc, bob, shareRepo, _, _, _) = newService(relay)
        val id = UUID.randomUUID()
        val unsigned = depositRow(id, aliceKeys.publicKey, bob.edPublicKey(), ByteArray(0))
        // Signed by a stranger, not by alice — claims to be from alice but doesn't verify against her key.
        val forged = unsigned.copy(senderSignature = signOpenAs(strangerKeys, unsigned))
        relay.pending = listOf(forged)
        relay.byId[id] = forged

        svc.syncInbox()

        assertTrue(relay.respondCalls.isEmpty())
        assertTrue(shareRepo.getAll().isEmpty())
    }

    @Test
    fun `syncInbox skips a Deposit from an unknown sender even with a self-consistent signature`() {
        val relay = FakeShareRelay()
        val (svc, bob, shareRepo, _, _, _) = newService(relay)
        val id = UUID.randomUUID()
        val unsigned = depositRow(id, strangerKeys.publicKey, bob.edPublicKey(), ByteArray(0))
        val row = unsigned.copy(senderSignature = signOpenAs(strangerKeys, unsigned))
        relay.pending = listOf(row)
        relay.byId[id] = row

        svc.syncInbox()

        assertTrue(relay.respondCalls.isEmpty())
        assertTrue(shareRepo.getAll().isEmpty())
    }

    @Test
    fun `listPendingRequests filters out a row with an unverifiable senderSignature`() {
        val relay = FakeShareRelay()
        val (svc, bob, _, _, _, _) = newService(relay)
        val id = UUID.randomUUID()
        val unsigned = depositRow(id, aliceKeys.publicKey, bob.edPublicKey(), ByteArray(0)).copy(transactionType = ShareTransactionType.REMOVAL)
        val forged = unsigned.copy(senderSignature = signOpenAs(strangerKeys, unsigned))
        relay.pending = listOf(forged)

        assertEquals(emptyList(), svc.listPendingRequests())
    }

    @Test
    fun `respond throws SignatureVerificationException when senderSignature doesn't verify`() {
        val relay = FakeShareRelay()
        val (svc, bob, _, _, _, _) = newService(relay)
        val id = UUID.randomUUID()
        val unsigned = depositRow(id, aliceKeys.publicKey, bob.edPublicKey(), ByteArray(0)).copy(transactionType = ShareTransactionType.REMOVAL)
        val forged = unsigned.copy(senderSignature = signOpenAs(strangerKeys, unsigned))
        relay.byId[id] = forged

        assertFailsWith<SignatureVerificationException> {
            svc.respond(id, approved = true)
        }
    }

    // ── Fan-out across a contact's BYOR relay (deposplit.com/CLAUDE.md's BYOR section) ──────────

    private class TwoRelayResolver(
        private val default: ShareRelay,
        private val byorUrl: String,
        private val byor: ShareRelay,
    ) : ShareRelayResolver {
        override fun resolve(relayBaseUrl: String?): ShareRelay = when (relayBaseUrl) {
            null -> default
            byorUrl -> byor
            else -> throw IllegalArgumentException("no fixture relay for $relayBaseUrl")
        }
    }

    @Test
    fun `syncInbox polls both the default relay and a contact's BYOR relay, merging results`() {
        val byorUrl = "http://byor.example:9000"
        val charlieKeys = TestKeyPair.generate()
        val charlieContact = aliceContact.copy(id = UUID.randomUUID(), pseudonym = "charlie", edPublicKey = charlieKeys.publicKey, relayBaseUrl = byorUrl)
        val defaultRelay = FakeShareRelay()
        val byorRelay = FakeShareRelay()
        val bobIdentity = IdentityService(InMemoryIdentityStoreForShareServiceTest())
        bobIdentity.register("bob")
        val shareRepo = FakeShareRepository()
        val contactRepo = FakeContactRepository(listOf(aliceContact, charlieContact))
        val svc = ShareService(
            relayResolver = TwoRelayResolver(defaultRelay, byorUrl, byorRelay),
            encryption = NoOpShareEncryption,
            shareRepository = shareRepo,
            shareMetadataRepository = FakeShareMetadataRepository(),
            secretRepository = FakeSecretRepository(),
            contactRepository = contactRepo,
            contactManagement = ContactService(contactRepo),
            keyConflictRepository = FakeKeyConflictRepository(),
            identity = bobIdentity,
        )

        val fromAliceId = UUID.randomUUID()
        val unsignedFromAlice = depositRow(fromAliceId, aliceKeys.publicKey, bobIdentity.edPublicKey(), ByteArray(0))
        val fromAlice = unsignedFromAlice.copy(senderSignature = signOpenAs(aliceKeys, unsignedFromAlice))
        val fromCharlieId = UUID.randomUUID()
        val unsignedFromCharlie = depositRow(fromCharlieId, charlieKeys.publicKey, bobIdentity.edPublicKey(), ByteArray(0))
        val fromCharlie = unsignedFromCharlie.copy(senderSignature = signOpenAs(charlieKeys, unsignedFromCharlie))
        defaultRelay.pending = listOf(fromAlice)
        defaultRelay.byId[fromAliceId] = fromAlice
        byorRelay.pending = listOf(fromCharlie)
        byorRelay.byId[fromCharlieId] = fromCharlie

        svc.syncInbox()

        assertEquals(listOf(fromAliceId), defaultRelay.respondCalls)
        assertEquals(listOf(fromCharlieId), byorRelay.respondCalls)
        assertEquals(setOf(fromAliceId, fromCharlieId), shareRepo.getAll().map { it.id }.toSet())
    }

    @Test
    fun `syncInbox still processes the reachable relay when the other is unreachable`() {
        val byorUrl = "http://byor.example:9000"
        val charlieKeys = TestKeyPair.generate()
        val charlieContact = aliceContact.copy(id = UUID.randomUUID(), pseudonym = "charlie", edPublicKey = charlieKeys.publicKey, relayBaseUrl = byorUrl)
        val defaultRelay = FakeShareRelay()
        val byorRelay = FakeShareRelay(unreachable = true)
        val bobIdentity = IdentityService(InMemoryIdentityStoreForShareServiceTest())
        bobIdentity.register("bob")
        val shareRepo = FakeShareRepository()
        val contactRepo = FakeContactRepository(listOf(aliceContact, charlieContact))
        val svc = ShareService(
            relayResolver = TwoRelayResolver(defaultRelay, byorUrl, byorRelay),
            encryption = NoOpShareEncryption,
            shareRepository = shareRepo,
            shareMetadataRepository = FakeShareMetadataRepository(),
            secretRepository = FakeSecretRepository(),
            contactRepository = contactRepo,
            contactManagement = ContactService(contactRepo),
            keyConflictRepository = FakeKeyConflictRepository(),
            identity = bobIdentity,
        )

        val fromAliceId = UUID.randomUUID()
        val unsignedFromAlice = depositRow(fromAliceId, aliceKeys.publicKey, bobIdentity.edPublicKey(), ByteArray(0))
        val fromAlice = unsignedFromAlice.copy(senderSignature = signOpenAs(aliceKeys, unsignedFromAlice))
        defaultRelay.pending = listOf(fromAlice)
        defaultRelay.byId[fromAliceId] = fromAlice

        svc.syncInbox()

        assertEquals(listOf(fromAliceId), defaultRelay.respondCalls)
        assertEquals(listOf(fromAliceId), shareRepo.getAll().map { it.id })
    }

    // ── Identity recovery (item 8) ──────────────────────────────────────────────

    private data class RecoveryFixture(
        val svc: ShareService,
        val bob: IdentityService,
        val shareRepo: FakeShareRepository,
        val secretRepo: FakeSecretRepository,
        val metaRepo: FakeShareMetadataRepository,
    )

    private fun newServiceForRecoveryTest(relay: FakeShareRelay): RecoveryFixture {
        val bobIdentity = IdentityService(InMemoryIdentityStoreForShareServiceTest())
        bobIdentity.register("bob")
        val shareRepo = FakeShareRepository()
        val secretRepo = FakeSecretRepository()
        val metaRepo = FakeShareMetadataRepository()
        val contactRepo = FakeContactRepository(listOf(aliceContact))
        val svc = ShareService(
            relayResolver = FixedShareRelayResolver(relay),
            encryption = NoOpShareEncryption,
            shareRepository = shareRepo,
            shareMetadataRepository = metaRepo,
            secretRepository = secretRepo,
            contactRepository = contactRepo,
            contactManagement = ContactService(contactRepo),
            keyConflictRepository = FakeKeyConflictRepository(),
            identity = bobIdentity,
        )
        return RecoveryFixture(svc, bobIdentity, shareRepo, secretRepo, metaRepo)
    }

    /** A self-approved recoveryMetadata row, as the relay would hand it back — APPROVED state and
     * respondedAt set at creation, since this type has no consent phase (see item 8).
     */
    private fun approvedRecoveryMetadataRow(
        secretId: UUID, senderKey: ByteArray, recipientKey: ByteArray, signer: TestKeyPair,
        k: Int = 2, n: Int = 3, label: String = "recovered secret",
    ): ShareRequest {
        val createdAt = Instant.now()
        val canon = PayloadCanonical.forOpen(secretId, ShareTransactionType.INVENTORY, recipientKey, label, createdAt, null, null, k, n)
        val sig = signer.sign(canon)
        val now = Instant.now()
        return ShareRequest(
            id = UUID.randomUUID(), secretId = secretId, senderKey = senderKey, recipientKey = recipientKey, label = label,
            secretCreatedAt = createdAt, transactionType = ShareTransactionType.INVENTORY, state = ShareRequestState.APPROVED,
            shareId = null, requestedAt = now, respondedAt = now, ciphertext = null, k = k, n = n,
            senderSignature = sig, recipientSignature = null,
        )
    }

    @Test
    fun `pushRecoveryMetadata opens a recoveryMetadata push for every HeldShare from that contact`() {
        val relay = FakeShareRelay()
        val (svc, _, shareRepo, _, _) = newServiceForRecoveryTest(relay)
        val secretId = UUID.randomUUID()
        shareRepo.save(
            HeldShare(
                id = UUID.randomUUID(), secretId = secretId, label = "test secret", contactId = aliceContact.id,
                senderPseudonym = "alice", createdAt = Instant.now(), pickedUpAt = Instant.now(),
                plaintextShare = byteArrayOf(9), k = 2, n = 3,
            )
        )

        svc.pushRecoveryMetadata(aliceContact.id)

        assertEquals(1, relay.openedRequests.size)
        val opened = relay.openedRequests.first()
        assertEquals(ShareTransactionType.INVENTORY, opened.transactionType)
        assertEquals(secretId, opened.secretId)
        assertTrue(opened.recipientKey.contentEquals(aliceContact.edPublicKey))
        assertEquals(2, opened.k)
        assertEquals(3, opened.n)
    }

    @Test
    fun `pushRecoveryMetadata throws for an unknown contact`() {
        val relay = FakeShareRelay()
        val (svc, _, _, _, _) = newServiceForRecoveryTest(relay)

        assertFailsWith<IllegalStateException> {
            svc.pushRecoveryMetadata(UUID.randomUUID())
        }
    }

    @Test
    fun `syncInbox processes an approved recoveryMetadata push and rebuilds Secret and ShareMetadata`() {
        val relay = FakeShareRelay()
        val (svc, bob, _, secretRepo, metaRepo) = newServiceForRecoveryTest(relay)
        val secretId = UUID.randomUUID()
        val pushRow = approvedRecoveryMetadataRow(secretId, aliceKeys.publicKey, bob.edPublicKey(), aliceKeys)
        relay.pending = listOf(pushRow)

        svc.syncInbox()

        val secrets = secretRepo.getAll()
        assertEquals(listOf(secretId), secrets.map { it.id })
        assertEquals(2, secrets.first().k)
        assertEquals(3, secrets.first().n)
        val metas = metaRepo.getAll()
        assertEquals(1, metas.size)
        assertEquals(secretId, metas.first().secretId)
        assertEquals(aliceContact.id, metas.first().contactId)
        // Consumed: deleted from the relay so it isn't reprocessed on the next poll.
        assertEquals(listOf(pushRow.id), relay.deletedRequestIds)
    }

    @Test
    fun `syncInbox ignores a recoveryMetadata push with a forged signature`() {
        val relay = FakeShareRelay()
        val (svc, bob, _, secretRepo, metaRepo) = newServiceForRecoveryTest(relay)
        val secretId = UUID.randomUUID()
        // Claims to be from alice but signed by a stranger.
        val pushRow = approvedRecoveryMetadataRow(secretId, aliceKeys.publicKey, bob.edPublicKey(), strangerKeys)
        relay.pending = listOf(pushRow)

        svc.syncInbox()

        assertTrue(secretRepo.getAll().isEmpty())
        assertTrue(metaRepo.getAll().isEmpty())
        assertTrue(relay.deletedRequestIds.isEmpty())
    }

    // ── Item 9: rotation push (client primitive + receive-side) and withdraw tombstone ──────────

    /** Builds a signed KeyRotation notice — the signing counterpart of relay.pushRotation.
     * [signer] is the party whose signature is attached; pass something other than the keypair
     * backing [oldEd25519Key] to build a forged notice.
     */
    private fun signedRotation(
        oldEd25519Key: ByteArray, recipientKey: ByteArray, signer: TestKeyPair,
        newEd25519Key: ByteArray = ByteArray(32) { 0x0a },
        newX25519Key: ByteArray = ByteArray(32) { 0x0b },
    ): KeyRotation {
        val canon = PayloadCanonical.forRotation(recipientKey, newEd25519Key, newX25519Key)
        val sig = signer.sign(canon)
        return KeyRotation(UUID.randomUUID(), oldEd25519Key, recipientKey, newEd25519Key, newX25519Key, sig, Instant.now())
    }

    @Test
    fun `pushRotation signs with the current identity and pushes to the contact's relay`() {
        val relay = FakeShareRelay()
        val (svc, bob, _, _, _, _) = newService(relay)
        val newEd = ByteArray(32) { 0x08 }
        val newX = ByteArray(32) { 0x09 }

        svc.pushRotation(aliceContact.id, newEd, newX)

        assertEquals(1, relay.pushedRotations.size)
        val pushed = relay.pushedRotations.first()
        assertTrue(pushed.recipientKey.contentEquals(aliceContact.edPublicKey))
        assertTrue(pushed.newEd25519Key.contentEquals(newEd))
        assertTrue(pushed.newX25519Key.contentEquals(newX))
        val canon = PayloadCanonical.forRotation(aliceContact.edPublicKey, newEd, newX)
        assertTrue(bob.verify(canon, pushed.signature, bob.edPublicKey()))
    }

    @Test
    fun `pushRotation throws for an unknown contact`() {
        val relay = FakeShareRelay()
        val (svc, _, _, _, _, _) = newService(relay)

        assertFailsWith<IllegalStateException> {
            svc.pushRotation(UUID.randomUUID(), ByteArray(32) { 0x01 }, ByteArray(32) { 0x02 })
        }
    }

    @Test
    fun `syncInbox auto-accepts a valid rotation notice and downgrades verification level to LOW`() {
        val relay = FakeShareRelay()
        // aliceContact starts at VERY_HIGH.
        val (svc, bob, _, contactRepo, _, _) = newService(relay)
        val newEd = ByteArray(32) { 0x0c }
        val newX = ByteArray(32) { 0x0d }
        val notice = signedRotation(aliceKeys.publicKey, bob.edPublicKey(), aliceKeys, newEd, newX)
        relay.rotationsToReturn = listOf(notice)

        svc.syncInbox()

        val updated = contactRepo.getById(aliceContact.id)
        assertEquals(aliceContact.id, updated?.id) // updated in place, contactId preserved
        assertTrue(updated!!.edPublicKey.contentEquals(newEd))
        assertTrue(updated.xPublicKey.contentEquals(newX))
        assertEquals(VerificationLevel.LOW, updated.verificationLevel)
        assertEquals(listOf(notice.id), relay.deletedRotationIds)
    }

    @Test
    fun `syncInbox never raises verification level above LOW even from an already-lower level`() {
        val relay = FakeShareRelay()
        val daveKeys = TestKeyPair.generate()
        val daveContact = aliceContact.copy(
            id = UUID.randomUUID(), pseudonym = "dave", edPublicKey = daveKeys.publicKey,
            verificationLevel = VerificationLevel.VERY_LOW,
        )
        val (svc, bob, _, contactRepo, _, _) = newService(relay, contacts = listOf(daveContact))
        val notice = signedRotation(daveKeys.publicKey, bob.edPublicKey(), daveKeys)
        relay.rotationsToReturn = listOf(notice)

        svc.syncInbox()

        // Continuity of key control is not a fresh personhood check (item 10) — it can never
        // raise the level, only cap it at LOW.
        assertEquals(VerificationLevel.VERY_LOW, contactRepo.getById(daveContact.id)?.verificationLevel)
    }

    @Test
    fun `syncInbox ignores a rotation notice with a forged signature`() {
        val relay = FakeShareRelay()
        val (svc, bob, _, contactRepo, _, _) = newService(relay)
        // Claims to be from alice (oldEd25519Key = aliceKeys.publicKey) but signed by a stranger.
        val notice = signedRotation(aliceKeys.publicKey, bob.edPublicKey(), strangerKeys)
        relay.rotationsToReturn = listOf(notice)

        svc.syncInbox()

        assertTrue(contactRepo.getById(aliceContact.id)!!.edPublicKey.contentEquals(aliceContact.edPublicKey))
        assertTrue(relay.deletedRotationIds.isEmpty())
    }

    @Test
    fun `syncInbox ignores a rotation notice from an unknown old key`() {
        val relay = FakeShareRelay()
        val (svc, bob, _, contactRepo, _, _) = newService(relay)
        val notice = signedRotation(strangerKeys.publicKey, bob.edPublicKey(), strangerKeys)
        relay.rotationsToReturn = listOf(notice)

        svc.syncInbox()

        assertEquals(listOf(aliceContact), contactRepo.getAll())
        assertTrue(relay.deletedRotationIds.isEmpty())
    }

    @Test
    fun `deleteHeldShare withdraws from the sender's relay scoped by secretId then deletes locally`() {
        val relay = FakeShareRelay()
        val (svc, _, shareRepo, _, _, _) = newService(relay)
        val secretId = UUID.randomUUID()
        val shareId = UUID.randomUUID()
        shareRepo.save(
            HeldShare(
                id = shareId, secretId = secretId, label = "x", contactId = aliceContact.id,
                senderPseudonym = "alice", createdAt = Instant.now(), pickedUpAt = Instant.now(),
                plaintextShare = byteArrayOf(1), k = 2, n = 3,
            )
        )

        svc.deleteHeldShare(shareId)

        assertEquals(listOf(FakeShareRelay.WithdrawCall(senderKey = null, secretId = secretId)), relay.withdrawCalls)
        assertTrue(shareRepo.getAll().isEmpty())
    }

    @Test
    fun `deleteAllHeldFromSender withdraws by senderKey then deletes all locally`() {
        val relay = FakeShareRelay()
        val (svc, _, shareRepo, _, _, _) = newService(relay)
        shareRepo.save(
            HeldShare(
                id = UUID.randomUUID(), secretId = UUID.randomUUID(), label = "x", contactId = aliceContact.id,
                senderPseudonym = "alice", createdAt = Instant.now(), pickedUpAt = Instant.now(),
                plaintextShare = byteArrayOf(1), k = 2, n = 3,
            )
        )
        shareRepo.save(
            HeldShare(
                id = UUID.randomUUID(), secretId = UUID.randomUUID(), label = "y", contactId = aliceContact.id,
                senderPseudonym = "alice", createdAt = Instant.now(), pickedUpAt = Instant.now(),
                plaintextShare = byteArrayOf(2), k = 2, n = 3,
            )
        )

        svc.deleteAllHeldFromSender(aliceContact.id)

        assertEquals(1, relay.withdrawCalls.size)
        assertTrue(relay.withdrawCalls.first().senderKey!!.contentEquals(aliceContact.edPublicKey))
        assertEquals(null, relay.withdrawCalls.first().secretId)
        assertTrue(shareRepo.getAll().isEmpty())
    }

    @Test
    fun `deleteHeldShare still deletes locally even if the withdraw call fails`() {
        val relay = FakeShareRelay()
        relay.throwOnWithdraw = true
        val (svc, _, shareRepo, _, _, _) = newService(relay)
        val shareId = UUID.randomUUID()
        shareRepo.save(
            HeldShare(
                id = shareId, secretId = UUID.randomUUID(), label = "x", contactId = aliceContact.id,
                senderPseudonym = "alice", createdAt = Instant.now(), pickedUpAt = Instant.now(),
                plaintextShare = byteArrayOf(1), k = 2, n = 3,
            )
        )

        svc.deleteHeldShare(shareId)

        assertTrue(shareRepo.getAll().isEmpty())
    }

    /** A bare deposit row shaped only for syncDistributed()'s purposes — that method never checks
     * signatures, so senderSignature is deliberately empty filler, not a genuine signature.
     */
    private fun bareDepositRow(id: UUID, secretId: UUID, recipientKey: ByteArray, state: ShareRequestState): ShareRequest =
        ShareRequest(
            id = id, secretId = secretId, senderKey = ByteArray(32) { 0x05 }, recipientKey = recipientKey,
            label = "test secret", secretCreatedAt = Instant.now(), transactionType = ShareTransactionType.DEPOSIT,
            state = state, shareId = null, requestedAt = Instant.now(),
            respondedAt = if (state == ShareRequestState.PENDING) null else Instant.now(),
            ciphertext = null, k = 2, n = 3, senderSignature = ByteArray(0), recipientSignature = null,
        )

    @Test
    fun `syncDistributed removes the local pointer and deletes the relay row for a withdrawn deposit`() {
        val relay = FakeShareRelay()
        val (svc, _, _, _, metaRepo, _) = newService(relay)
        val depositId = UUID.randomUUID()
        val secretId = UUID.randomUUID()
        metaRepo.save(ShareMetadata(depositId, secretId, aliceContact.id))
        relay.pending = listOf(bareDepositRow(depositId, secretId, aliceContact.edPublicKey, ShareRequestState.WITHDRAWN))

        svc.syncDistributed()

        assertTrue(metaRepo.getAll().isEmpty())
        assertEquals(listOf(depositId), relay.deletedRequestIds)
    }

    @Test
    fun `syncDistributed still upserts normally for a non-withdrawn row`() {
        val relay = FakeShareRelay()
        val (svc, _, _, _, metaRepo, _) = newService(relay)
        val depositId = UUID.randomUUID()
        val secretId = UUID.randomUUID()
        relay.pending = listOf(bareDepositRow(depositId, secretId, aliceContact.edPublicKey, ShareRequestState.APPROVED))

        svc.syncDistributed()

        assertEquals(listOf(depositId), metaRepo.getAll().map { it.id })
        assertTrue(relay.deletedRequestIds.isEmpty())
    }

    // ── Item 10: stolen-key revocation (compromised-key flag + key conflicts) ───────────────────

    @Test
    fun `syncInbox refuses auto-accept and captures a key conflict when the old key is revoked`() {
        val relay = FakeShareRelay()
        val revokedAliceContact = aliceContact.copy(revokedEdKeys = listOf(aliceKeys.publicKey))
        val (svc, bob, _, contactRepo, _, conflictRepo) = newService(relay, contacts = listOf(revokedAliceContact))
        val newEd = ByteArray(32) { 0x0e }
        val newX = ByteArray(32) { 0x0f }
        val notice = signedRotation(aliceKeys.publicKey, bob.edPublicKey(), aliceKeys, newEd, newX)
        relay.rotationsToReturn = listOf(notice)

        svc.syncInbox()

        // Not auto-accepted: the contact record is untouched.
        val stillCurrent = contactRepo.getById(revokedAliceContact.id)
        assertTrue(stillCurrent!!.edPublicKey.contentEquals(revokedAliceContact.edPublicKey))
        assertEquals(VerificationLevel.VERY_HIGH, stillCurrent.verificationLevel)
        // Captured locally instead — durable, not dependent on the relay still having the notice.
        val conflicts = conflictRepo.getAll()
        assertEquals(1, conflicts.size)
        assertEquals(revokedAliceContact.id, conflicts.first().contactId)
        assertTrue(conflicts.first().newEd25519Key.contentEquals(newEd))
        assertTrue(conflicts.first().newX25519Key.contentEquals(newX))
        // The relay notice is consumed either way — the local KeyConflict record is now the
        // durable copy.
        assertEquals(listOf(notice.id), relay.deletedRotationIds)
    }

    @Test
    fun `syncInbox still auto-accepts a non-revoked rotation`() {
        val relay = FakeShareRelay()
        // Some unrelated historical key, not the one this notice claims continuity from.
        val contactWithUnrelatedRevocation = aliceContact.copy(revokedEdKeys = listOf(ByteArray(32) { 0x99.toByte() }))
        val (svc, bob, _, contactRepo, _, conflictRepo) = newService(relay, contacts = listOf(contactWithUnrelatedRevocation))
        val newEd = ByteArray(32) { 0x10 }
        val notice = signedRotation(aliceKeys.publicKey, bob.edPublicKey(), aliceKeys, newEd)
        relay.rotationsToReturn = listOf(notice)

        svc.syncInbox()

        assertTrue(contactRepo.getById(aliceContact.id)!!.edPublicKey.contentEquals(newEd))
        assertTrue(conflictRepo.getAll().isEmpty())
    }

    @Test
    fun `listAndDismissKeyConflict round-trips`() {
        val relay = FakeShareRelay()
        val (svc, _, _, _, _, conflictRepo) = newService(relay)
        val conflict = KeyConflict(
            id = UUID.randomUUID(), contactId = aliceContact.id, oldEd25519Key = aliceKeys.publicKey,
            newEd25519Key = ByteArray(32) { 0x01 }, newX25519Key = ByteArray(32) { 0x02 }, detectedAt = Instant.now(),
        )
        conflictRepo.save(conflict)

        assertEquals(listOf(conflict), svc.listKeyConflicts())

        svc.dismissKeyConflict(conflict.id)

        assertTrue(svc.listKeyConflicts().isEmpty())
    }

}
