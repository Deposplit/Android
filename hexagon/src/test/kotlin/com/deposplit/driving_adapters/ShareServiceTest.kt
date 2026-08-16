package com.deposplit.driving_adapters

import com.deposplit.driven_ports.ContactRepository
import com.deposplit.driven_ports.IdentityStore
import com.deposplit.driven_ports.SecretRepository
import com.deposplit.driven_ports.ShareMetadataRepository
import com.deposplit.driven_ports.ShareRelay
import com.deposplit.driven_ports.ShareRelayResolver
import com.deposplit.driven_ports.ShareRepository
import com.deposplit.value_objects.Contact
import com.deposplit.value_objects.HeldShare
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

private class FakeContactRepository(private val contacts: List<Contact>) : ContactRepository {
    override fun getAll() = contacts
    override fun getByEdKey(edPublicKey: ByteArray) = contacts.find { it.edPublicKey.contentEquals(edPublicKey) }
    override fun getById(id: UUID) = contacts.find { it.id == id }
    override fun save(contact: Contact) {}
    override fun delete(contactId: UUID) {}
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

    private fun newService(relay: FakeShareRelay): Triple<ShareService, IdentityService, FakeShareRepository> {
        val bobIdentity = IdentityService(InMemoryIdentityStoreForShareServiceTest())
        bobIdentity.register("bob")
        val shareRepo = FakeShareRepository()
        val svc = ShareService(
            relayResolver = FixedShareRelayResolver(relay),
            encryption = NoOpShareEncryption,
            shareRepository = shareRepo,
            shareMetadataRepository = FakeShareMetadataRepository(),
            secretRepository = FakeSecretRepository(),
            contactRepository = FakeContactRepository(listOf(aliceContact)),
            identity = bobIdentity,
        )
        return Triple(svc, bobIdentity, shareRepo)
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
        val (svc, bob, shareRepo) = newService(relay)
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
        val (svc, bob, shareRepo) = newService(relay)
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
        val (svc, bob, shareRepo) = newService(relay)
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
        val (svc, bob, _) = newService(relay)
        val id = UUID.randomUUID()
        val unsigned = depositRow(id, aliceKeys.publicKey, bob.edPublicKey(), ByteArray(0)).copy(transactionType = ShareTransactionType.REMOVAL)
        val forged = unsigned.copy(senderSignature = signOpenAs(strangerKeys, unsigned))
        relay.pending = listOf(forged)

        assertEquals(emptyList(), svc.listPendingRequests())
    }

    @Test
    fun `respond throws SignatureVerificationException when senderSignature doesn't verify`() {
        val relay = FakeShareRelay()
        val (svc, bob, _) = newService(relay)
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
        val svc = ShareService(
            relayResolver = TwoRelayResolver(defaultRelay, byorUrl, byorRelay),
            encryption = NoOpShareEncryption,
            shareRepository = shareRepo,
            shareMetadataRepository = FakeShareMetadataRepository(),
            secretRepository = FakeSecretRepository(),
            contactRepository = FakeContactRepository(listOf(aliceContact, charlieContact)),
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
        val svc = ShareService(
            relayResolver = TwoRelayResolver(defaultRelay, byorUrl, byorRelay),
            encryption = NoOpShareEncryption,
            shareRepository = shareRepo,
            shareMetadataRepository = FakeShareMetadataRepository(),
            secretRepository = FakeSecretRepository(),
            contactRepository = FakeContactRepository(listOf(aliceContact, charlieContact)),
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
        val svc = ShareService(
            relayResolver = FixedShareRelayResolver(relay),
            encryption = NoOpShareEncryption,
            shareRepository = shareRepo,
            shareMetadataRepository = metaRepo,
            secretRepository = secretRepo,
            contactRepository = FakeContactRepository(listOf(aliceContact)),
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
}
