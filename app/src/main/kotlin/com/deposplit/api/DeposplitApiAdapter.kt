package com.deposplit.api

import com.deposplit.driving_ports.Identity
import com.deposplit.driven_ports.ShareRelay
import com.deposplit.value_objects.CipherSuite
import com.deposplit.value_objects.CustodyHeartbeat
import com.deposplit.value_objects.KeyRotation
import com.deposplit.value_objects.MimeType
import com.deposplit.value_objects.Role
import com.deposplit.value_objects.ShareRequest
import com.deposplit.value_objects.ShareRequestState
import com.deposplit.value_objects.ShareTransactionType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

class ApiException(val statusCode: Int, body: String) : Exception("HTTP $statusCode: $body")

class DeposplitApiAdapter(
    private val auth: Identity,
    private val baseUrl: String = RelayDefaults.FALLBACK_BASE_URL,
) : ShareRelay {

    private val json = Json { ignoreUnknownKeys = true }

    override fun openShareRequest(
        secretId: UUID,
        recipientKey: ByteArray,
        label: String,
        secretCreatedAt: Instant,
        transactionType: ShareTransactionType,
        ciphertext: ByteArray?,
        k: Int?,
        n: Int?,
        mimeType: MimeType?,
        senderSignature: ByteArray,
    ): ShareRequest {
        val body = json.encodeToString(
            OpenShareRequestJson(
                secretId = secretId.toString(),
                recipientKey = recipientKey.encodeBase64Url(),
                label = label,
                secretCreatedAt = secretCreatedAt.toString(),
                transactionType = transactionType.wireValue,
                ciphertext = ciphertext?.encodeBase64(),
                k = k,
                n = n,
                mimeType = mimeType?.value,
                senderSignature = senderSignature.encodeBase64Url(),
            )
        )
        return json.decodeFromString<ShareRequestJson>(execute("POST", "/share-requests", body)).toDomain()
    }

    override fun listShareRequests(role: Role, transactionType: ShareTransactionType?, state: ShareRequestState?): List<ShareRequest> {
        val query = buildString {
            append("?role=${role.name.lowercase()}")
            if (transactionType != null) append("&type=${transactionType.wireValue}")
            if (state != null) append("&state=${state.name.lowercase()}")
        }
        return json.decodeFromString<List<ShareRequestJson>>(execute("GET", "/share-requests$query"))
            .map { it.toDomain() }
    }

    override fun getShareRequest(requestId: UUID): ShareRequest =
        json.decodeFromString<ShareRequestJson>(execute("GET", "/share-requests/$requestId")).toDomain()

    override fun respondToShareRequest(
        requestId: UUID,
        approved: Boolean,
        ciphertext: ByteArray?,
        recipientSignature: ByteArray,
    ): ShareRequest {
        val body = json.encodeToString(
            RespondJson(
                state = if (approved) "approved" else "denied",
                ciphertext = ciphertext?.encodeBase64(),
                recipientSignature = recipientSignature.encodeBase64Url(),
            )
        )
        return json.decodeFromString<ShareRequestJson>(
            execute("PATCH", "/share-requests/$requestId", body)
        ).toDomain()
    }

    override fun deleteShareRequest(requestId: UUID) {
        execute("DELETE", "/share-requests/$requestId")
    }

    override fun deleteShareRequests(senderKey: ByteArray?, secretId: UUID?) {
        val query = buildString {
            var first = true
            if (senderKey != null) {
                append("?senderKey=${senderKey.encodeBase64Url()}")
                first = false
            }
            if (secretId != null) {
                append(if (first) "?" else "&")
                append("secretId=$secretId")
            }
        }
        execute("DELETE", "/share-requests$query")
    }

    override fun withdrawShareRequests(senderKey: ByteArray?, secretId: UUID?) {
        val query = buildString {
            var first = true
            if (senderKey != null) {
                append("?senderKey=${senderKey.encodeBase64Url()}")
                first = false
            }
            if (secretId != null) {
                append(if (first) "?" else "&")
                append("secretId=$secretId")
            }
        }
        execute("POST", "/share-requests/withdraw$query")
    }

    override fun pushRotation(recipientKey: ByteArray, newVerifyKey: ByteArray, newEncKey: ByteArray, newCipherSuite: CipherSuite, signature: ByteArray) {
        val body = json.encodeToString(
            PushRotationJson(
                recipientKey = recipientKey.encodeBase64Url(),
                newVerifyKey = newVerifyKey.encodeBase64Url(),
                newEncKey = newEncKey.encodeBase64Url(),
                newCipherSuite = newCipherSuite.wireValue,
                signature = signature.encodeBase64Url(),
            )
        )
        execute("POST", "/key-rotations", body)
    }

    override fun listRotations(): List<KeyRotation> =
        json.decodeFromString<List<KeyRotationJson>>(execute("GET", "/key-rotations")).map { it.toDomain() }

    override fun deleteRotation(id: UUID) {
        execute("DELETE", "/key-rotations/$id")
    }

    override fun pushHeartbeat(ownerKey: ByteArray, secretIds: List<UUID>, optedOut: Boolean, signature: ByteArray) {
        val body = json.encodeToString(
            PushHeartbeatJson(
                ownerKey = ownerKey.encodeBase64Url(),
                secretIds = secretIds.map { it.toString() },
                optedOut = optedOut,
                signature = signature.encodeBase64Url(),
            )
        )
        execute("POST", "/custody-heartbeats", body)
    }

    override fun listHeartbeats(): List<CustodyHeartbeat> =
        json.decodeFromString<List<CustodyHeartbeatJson>>(execute("GET", "/custody-heartbeats")).map { it.toDomain() }

    // ── HTTP ─────────────────────────────────────────────────────────────────

    private fun execute(method: String, path: String, body: String? = null): String {
        val nonce = generateNonce()
        val bodyBytes = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val canonical = buildCanonical(nonce, method, path, bodyBytes)
        val sig = auth.sign(canonical.toByteArray(Charsets.UTF_8))

        val conn = URL("$baseUrl$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("X-Deposplit-Verify-Key", auth.verifyKey().encodeBase64Url())
            conn.setRequestProperty("X-Deposplit-Nonce", nonce)
            conn.setRequestProperty("X-Deposplit-Signature", sig.encodeBase64Url())
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(bodyBytes) }
            }
            val code = conn.responseCode
            return when {
                code == 204 -> ""
                code < 400 -> conn.inputStream.use { it.reader().readText() }
                else -> throw ApiException(code, conn.errorStream?.use { it.reader().readText() } ?: "")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun generateNonce(): String {
        val random = ByteArray(8).also { secureRandom.nextBytes(it) }
        return "${System.currentTimeMillis()}.${random.joinToString("") { "%02x".format(it) }}"
    }

    private fun buildCanonical(nonce: String, method: String, path: String, body: ByteArray): String {
        val bodyHash = MessageDigest.getInstance("SHA-256").digest(body)
            .joinToString("") { "%02x".format(it) }
        return "$nonce\n${method.uppercase()}\n$path\n$bodyHash"
    }

    // ── JSON wire types ───────────────────────────────────────────────────────

    @Serializable
    private data class OpenShareRequestJson(
        val secretId: String,
        val recipientKey: String,
        val label: String,
        val secretCreatedAt: String,
        val transactionType: String,
        val ciphertext: String? = null,
        val k: Int? = null,
        val n: Int? = null,
        val mimeType: String? = null,
        val senderSignature: String,
    )

    @Serializable
    private data class RespondJson(val state: String, val ciphertext: String? = null, val recipientSignature: String)

    @Serializable
    private data class PushRotationJson(
        val recipientKey: String,
        val newVerifyKey: String,
        val newEncKey: String,
        val newCipherSuite: String,
        val signature: String,
    )

    @Serializable
    private data class KeyRotationJson(
        val id: String,
        val oldVerifyKey: String,
        val recipientKey: String,
        val newVerifyKey: String,
        val newEncKey: String,
        val newCipherSuite: String,
        val signature: String,
        val createdAt: String,
    )

    @Serializable
    private data class PushHeartbeatJson(
        val ownerKey: String,
        val secretIds: List<String>,
        val optedOut: Boolean,
        val signature: String,
    )

    @Serializable
    private data class CustodyHeartbeatJson(
        val id: String,
        val holderKey: String,
        val ownerKey: String,
        val secretIds: List<String>,
        val optedOut: Boolean,
        val signature: String,
        val createdAt: String,
    )

    @Serializable
    private data class ShareRequestJson(
        val id: String,
        val secretId: String,
        val senderKey: String,
        val recipientKey: String,
        val label: String,
        val secretCreatedAt: String,
        val transactionType: String,
        val state: String,
        val requestedAt: String,
        val respondedAt: String? = null,
        val ciphertext: String? = null,
        val k: Int? = null,
        val n: Int? = null,
        val mimeType: String? = null,
        val senderSignature: String,
        val recipientSignature: String? = null,
    )

    // ── Domain conversions ────────────────────────────────────────────────────

    private fun ShareRequestJson.toDomain() = ShareRequest(
        id = UUID.fromString(id),
        secretId = UUID.fromString(secretId),
        senderKey = senderKey.decodeBase64Url(),
        recipientKey = recipientKey.decodeBase64Url(),
        label = label,
        secretCreatedAt = Instant.parse(secretCreatedAt),
        transactionType = ShareTransactionType.fromWire(transactionType) ?: error("Unknown transactionType: $transactionType"),
        state = when (state) {
            "pending" -> ShareRequestState.PENDING
            "approved" -> ShareRequestState.APPROVED
            "denied" -> ShareRequestState.DENIED
            "withdrawn" -> ShareRequestState.WITHDRAWN
            else -> error("Unknown state: $state")
        },
        requestedAt = Instant.parse(requestedAt),
        respondedAt = respondedAt?.let { Instant.parse(it) },
        ciphertext = ciphertext?.decodeBase64(),
        k = k,
        n = n,
        mimeType = mimeType?.let(::MimeType),
        senderSignature = senderSignature.decodeBase64Url(),
        recipientSignature = recipientSignature?.decodeBase64Url(),
    )

    private fun KeyRotationJson.toDomain() = KeyRotation(
        id = UUID.fromString(id),
        oldVerifyKey = oldVerifyKey.decodeBase64Url(),
        recipientKey = recipientKey.decodeBase64Url(),
        newVerifyKey = newVerifyKey.decodeBase64Url(),
        newEncKey = newEncKey.decodeBase64Url(),
        newCipherSuite = CipherSuite.fromWire(newCipherSuite) ?: error("Unknown cipher suite: $newCipherSuite"),
        signature = signature.decodeBase64Url(),
        createdAt = Instant.parse(createdAt),
    )

    private fun CustodyHeartbeatJson.toDomain() = CustodyHeartbeat(
        id = UUID.fromString(id),
        holderKey = holderKey.decodeBase64Url(),
        ownerKey = ownerKey.decodeBase64Url(),
        secretIds = secretIds.map { UUID.fromString(it) },
        optedOut = optedOut,
        signature = signature.decodeBase64Url(),
        createdAt = Instant.parse(createdAt),
    )

    companion object {
        private val secureRandom = SecureRandom()
    }
}

private fun ByteArray.encodeBase64Url(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(this)

private fun ByteArray.encodeBase64(): String =
    Base64.getEncoder().encodeToString(this)

private fun String.decodeBase64Url(): ByteArray = Base64.getUrlDecoder().decode(this)

private fun String.decodeBase64(): ByteArray = Base64.getDecoder().decode(this)
