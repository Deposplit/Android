package com.deposplit.driving_adapters

import com.deposplit.driven_ports.ContactRepository
import com.deposplit.driven_ports.IdentityStore
import com.deposplit.driven_ports.ShareMetadataRepository
import com.deposplit.driven_ports.ShareRelay
import com.deposplit.driven_ports.ShareRelayResolver
import com.deposplit.driven_ports.ShareRepository
import com.deposplit.value_objects.Contact
import com.deposplit.value_objects.HeldShare
import com.deposplit.value_objects.PayloadCanonical
import com.deposplit.value_objects.Role
import com.deposplit.value_objects.ShareMetadata
import com.deposplit.value_objects.ShareRequest
import com.deposplit.value_objects.ShareRequestState
import com.deposplit.value_objects.ShareRequestType
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
    override fun save(contact: Contact) {}
    override fun delete(contactId: UUID) {}
}

private class FakeShareRepository : ShareRepository {
    private val shares = mutableListOf<HeldShare>()
    override fun getAll() = shares.toList()
    override fun getCiphertext(shareId: UUID) = shares.find { it.id == shareId }?.ciphertext
    override fun save(share: HeldShare) { shares.add(share) }
    override fun delete(shareId: UUID) { shares.removeAll { it.id == shareId } }
}

private class FakeShareMetadataRepository : ShareMetadataRepository {
    private val metas = mutableListOf<ShareMetadata>()
    override fun getAll() = metas.toList()
    override fun save(share: ShareMetadata) { metas.add(share) }
    override fun delete(shareId: UUID) { metas.removeAll { it.id == shareId } }
}

private object NoOpShareEncryption : ShareEncryption {
    override fun encrypt(plaintext: ByteArray, recipientXPublicKey: ByteArray) = plaintext
    override fun decrypt(noncePlusCiphertext: ByteArray, recipientXPublicKey: ByteArray) = noncePlusCiphertext
}

/** In-memory ShareRelay test double — listShareRequests ignores its filters and just returns
 * whatever [pending] is configured to, which is all these tests need.
 */
private class FakeShareRelay(var unreachable: Boolean = false) : ShareRelay {
    var pending: List<ShareRequest> = emptyList()
    var byId: MutableMap<UUID, ShareRequest> = mutableMapOf()
    val respondCalls = mutableListOf<UUID>()

    override fun openShareRequest(
        secretId: UUID, recipientKey: ByteArray, label: String, secretCreatedAt: Instant,
        requestType: ShareRequestType, shareId: UUID?, ciphertext: ByteArray?, senderSignature: ByteArray,
    ): ShareRequest = throw UnsupportedOperationException("not exercised by these tests")

    override fun listShareRequests(role: Role, requestType: ShareRequestType?, state: ShareRequestState?): List<ShareRequest> {
        if (unreachable) throw RuntimeException("simulated relay outage")
        return pending
    }

    override fun getShareRequest(requestId: UUID): ShareRequest = byId.getValue(requestId)

    override fun respondToShareRequest(requestId: UUID, approved: Boolean, ciphertext: ByteArray?, recipientSignature: ByteArray): ShareRequest {
        respondCalls.add(requestId)
        val updated = byId.getValue(requestId).copy(state = if (approved) ShareRequestState.APPROVED else ShareRequestState.DENIED)
        byId[requestId] = updated
        return updated
    }

    override fun deleteShareRequest(requestId: UUID) {}
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
            contactRepository = FakeContactRepository(listOf(aliceContact)),
            identity = bobIdentity,
        )
        return Triple(svc, bobIdentity, shareRepo)
    }

    private fun pickUpRow(id: UUID, senderKey: ByteArray, recipientKey: ByteArray, senderSignature: ByteArray, ciphertext: ByteArray = byteArrayOf(1, 2, 3)): ShareRequest =
        ShareRequest(
            id = id,
            secretId = UUID.randomUUID(),
            senderKey = senderKey,
            recipientKey = recipientKey,
            label = "test secret",
            secretCreatedAt = Instant.now(),
            requestType = ShareRequestType.PICK_UP,
            state = ShareRequestState.PENDING,
            shareId = null,
            requestedAt = Instant.now(),
            respondedAt = null,
            ciphertext = ciphertext,
            senderSignature = senderSignature,
            recipientSignature = null,
        )

    private fun signOpenAs(signer: TestKeyPair, row: ShareRequest): ByteArray =
        signer.sign(PayloadCanonical.forOpen(row.secretId, row.requestType, row.recipientKey, row.label, row.secretCreatedAt, row.shareId, row.ciphertext))

    @Test
    fun `syncInbox approves and saves a PickUp with a valid senderSignature from a known contact`() {
        val relay = FakeShareRelay()
        val (svc, bob, shareRepo) = newService(relay)
        val id = UUID.randomUUID()
        val unsigned = pickUpRow(id, aliceKeys.publicKey, bob.edPublicKey(), ByteArray(0))
        val row = unsigned.copy(senderSignature = signOpenAs(aliceKeys, unsigned))
        relay.pending = listOf(row)
        relay.byId[id] = row

        svc.syncInbox()

        assertEquals(listOf(id), relay.respondCalls)
        assertEquals(listOf(id), shareRepo.getAll().map { it.id })
    }

    @Test
    fun `syncInbox skips a PickUp whose senderSignature doesn't verify against the claimed sender`() {
        val relay = FakeShareRelay()
        val (svc, bob, shareRepo) = newService(relay)
        val id = UUID.randomUUID()
        val unsigned = pickUpRow(id, aliceKeys.publicKey, bob.edPublicKey(), ByteArray(0))
        // Signed by a stranger, not by alice — claims to be from alice but doesn't verify against her key.
        val forged = unsigned.copy(senderSignature = signOpenAs(strangerKeys, unsigned))
        relay.pending = listOf(forged)
        relay.byId[id] = forged

        svc.syncInbox()

        assertTrue(relay.respondCalls.isEmpty())
        assertTrue(shareRepo.getAll().isEmpty())
    }

    @Test
    fun `syncInbox skips a PickUp from an unknown sender even with a self-consistent signature`() {
        val relay = FakeShareRelay()
        val (svc, bob, shareRepo) = newService(relay)
        val id = UUID.randomUUID()
        val unsigned = pickUpRow(id, strangerKeys.publicKey, bob.edPublicKey(), ByteArray(0))
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
        val unsigned = pickUpRow(id, aliceKeys.publicKey, bob.edPublicKey(), ByteArray(0)).copy(requestType = ShareRequestType.DELETE)
        val forged = unsigned.copy(senderSignature = signOpenAs(strangerKeys, unsigned))
        relay.pending = listOf(forged)

        assertEquals(emptyList(), svc.listPendingRequests())
    }

    @Test
    fun `respond throws SignatureVerificationException when senderSignature doesn't verify`() {
        val relay = FakeShareRelay()
        val (svc, bob, _) = newService(relay)
        val id = UUID.randomUUID()
        val unsigned = pickUpRow(id, aliceKeys.publicKey, bob.edPublicKey(), ByteArray(0)).copy(requestType = ShareRequestType.DELETE)
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
            contactRepository = FakeContactRepository(listOf(aliceContact, charlieContact)),
            identity = bobIdentity,
        )

        val fromAliceId = UUID.randomUUID()
        val unsignedFromAlice = pickUpRow(fromAliceId, aliceKeys.publicKey, bobIdentity.edPublicKey(), ByteArray(0))
        val fromAlice = unsignedFromAlice.copy(senderSignature = signOpenAs(aliceKeys, unsignedFromAlice))
        val fromCharlieId = UUID.randomUUID()
        val unsignedFromCharlie = pickUpRow(fromCharlieId, charlieKeys.publicKey, bobIdentity.edPublicKey(), ByteArray(0))
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
            contactRepository = FakeContactRepository(listOf(aliceContact, charlieContact)),
            identity = bobIdentity,
        )

        val fromAliceId = UUID.randomUUID()
        val unsignedFromAlice = pickUpRow(fromAliceId, aliceKeys.publicKey, bobIdentity.edPublicKey(), ByteArray(0))
        val fromAlice = unsignedFromAlice.copy(senderSignature = signOpenAs(aliceKeys, unsignedFromAlice))
        defaultRelay.pending = listOf(fromAlice)
        defaultRelay.byId[fromAliceId] = fromAlice

        svc.syncInbox()

        assertEquals(listOf(fromAliceId), defaultRelay.respondCalls)
        assertEquals(listOf(fromAliceId), shareRepo.getAll().map { it.id })
    }
}
