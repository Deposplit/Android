package com.deposplit.api

import com.deposplit.driving_ports.Identity
import com.deposplit.driven_ports.ShareRelay
import com.deposplit.value_objects.Role
import com.deposplit.value_objects.ShareRequest
import com.deposplit.value_objects.ShareRequestState
import com.deposplit.value_objects.ShareRequestType
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
    private val baseUrl: String = "https://api.deposplit.com",
) : ShareRelay {

    private val json = Json { ignoreUnknownKeys = true }

    override fun openShareRequest(
        secretId: UUID,
        recipientKey: ByteArray,
        label: String,
        secretCreatedAt: Instant,
        requestType: ShareRequestType,
        shareId: UUID?,
        ciphertext: ByteArray?,
        senderSignature: ByteArray,
    ): ShareRequest {
        val body = json.encodeToString(
            OpenShareRequestJson(
                secretId = secretId.toString(),
                recipientKey = recipientKey.encodeBase64Url(),
                label = label,
                secretCreatedAt = secretCreatedAt.toString(),
                requestType = requestType.toWire(),
                shareId = shareId?.toString(),
                ciphertext = ciphertext?.encodeBase64(),
                senderSignature = senderSignature.encodeBase64Url(),
            )
        )
        return json.decodeFromString<ShareRequestJson>(execute("POST", "/share-requests", body)).toDomain()
    }

    override fun listShareRequests(role: Role, requestType: ShareRequestType?, state: ShareRequestState?): List<ShareRequest> {
        val query = buildString {
            append("?role=${role.name.lowercase()}")
            if (requestType != null) append("&type=${requestType.toWire()}")
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
            conn.setRequestProperty("X-Deposplit-Public-Key", auth.edPublicKey().encodeBase64Url())
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
        val requestType: String,
        val shareId: String? = null,
        val ciphertext: String? = null,
        val senderSignature: String,
    )

    @Serializable
    private data class RespondJson(val state: String, val ciphertext: String? = null, val recipientSignature: String)

    @Serializable
    private data class ShareRequestJson(
        val id: String,
        val secretId: String,
        val senderKey: String,
        val recipientKey: String,
        val label: String,
        val secretCreatedAt: String,
        val requestType: String,
        val state: String,
        val shareId: String? = null,
        val requestedAt: String,
        val respondedAt: String? = null,
        val ciphertext: String? = null,
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
        requestType = when (requestType) {
            "pick_up" -> ShareRequestType.PICK_UP
            "retrieve" -> ShareRequestType.RETRIEVE
            "delete" -> ShareRequestType.DELETE
            else -> error("Unknown requestType: $requestType")
        },
        state = when (state) {
            "pending" -> ShareRequestState.PENDING
            "approved" -> ShareRequestState.APPROVED
            "denied" -> ShareRequestState.DENIED
            else -> error("Unknown state: $state")
        },
        shareId = shareId?.let { UUID.fromString(it) },
        requestedAt = Instant.parse(requestedAt),
        respondedAt = respondedAt?.let { Instant.parse(it) },
        ciphertext = ciphertext?.decodeBase64(),
        senderSignature = senderSignature.decodeBase64Url(),
        recipientSignature = recipientSignature?.decodeBase64Url(),
    )

    private fun ShareRequestType.toWire(): String = when (this) {
        ShareRequestType.PICK_UP -> "pick_up"
        ShareRequestType.RETRIEVE -> "retrieve"
        ShareRequestType.DELETE -> "delete"
    }

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
