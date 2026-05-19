package com.deposplit.api

import com.deposplit.driven_ports.ShareRepository
import com.deposplit.driving_ports.AuthPort
import com.deposplit.driving_ports.ShareTransport
import com.deposplit.value_objects.Role
import com.deposplit.value_objects.ShareMetadata
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
import java.util.Base64
import java.util.UUID

class ApiException(val statusCode: Int, body: String) : Exception("HTTP $statusCode: $body")

class DeposplitApiAdapter(
    private val auth: AuthPort,
    private val baseUrl: String = "https://api.deposplit.com/v1",
) : ShareTransport {

    private val json = Json { ignoreUnknownKeys = true }

    override fun depositShare(
        secretId: UUID,
        label: String,
        recipientKey: ByteArray,
        ciphertext: ByteArray,
    ): ShareMetadata {
        val body = json.encodeToString(
            ShareDepositJson(
                secretId = secretId.toString(),
                label = label,
                recipientKey = recipientKey.encodeBase64Url(),
                ciphertext = ciphertext.encodeBase64(),
            )
        )
        return json.decodeFromString<ShareMetadataJson>(execute("POST", "/shares", body)).toDomain()
    }

    override fun listShares(role: Role, counterpartyKey: ByteArray?): List<ShareMetadata> {
        val query = buildString {
            append("?role=${role.name.lowercase()}")
            if (counterpartyKey != null) append("&counterpartyKey=${counterpartyKey.encodeBase64Url()}")
        }
        return json.decodeFromString<List<ShareMetadataJson>>(execute("GET", "/shares$query"))
            .map { it.toDomain() }
    }

    override fun pickUpShare(shareId: UUID): ByteArray {
        val body = execute("GET", "/shares/$shareId")
        return json.decodeFromString<PickUpShareResponseJson>(body).ciphertext.decodeBase64()
    }

    override fun deleteShare(shareId: UUID) {
        execute("DELETE", "/shares/$shareId")
    }

    override fun openShareRequest(shareId: UUID, type: ShareRequestType): ShareRequest {
        val body = json.encodeToString(
            OpenShareRequestJson(
                shareId = shareId.toString(),
                requestType = type.name.lowercase(),
            )
        )
        return json.decodeFromString<ShareRequestJson>(execute("POST", "/share-requests", body)).toDomain()
    }

    override fun listShareRequests(role: Role, state: ShareRequestState?): List<ShareRequest> {
        val query = buildString {
            append("?role=${role.name.lowercase()}")
            if (state != null) append("&state=${state.name.lowercase()}")
        }
        return json.decodeFromString<List<ShareRequestJson>>(execute("GET", "/share-requests$query"))
            .map { it.toDomain() }
    }

    override fun getShareRequest(requestId: UUID): ShareRequest =
        json.decodeFromString<ShareRequestJson>(execute("GET", "/share-requests/$requestId")).toDomain()

    override fun respondToShareRequest(requestId: UUID, approved: Boolean, ciphertext: ByteArray?): ShareRequest {
        val body = json.encodeToString(
            RespondJson(
                state = if (approved) "approved" else "denied",
                ciphertext = ciphertext?.encodeBase64(),
            )
        )
        return json.decodeFromString<ShareRequestJson>(
            execute("PATCH", "/share-requests/$requestId", body)
        ).toDomain()
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
    private data class ShareDepositJson(
        val secretId: String,
        val label: String,
        val recipientKey: String,
        val ciphertext: String,
    )

    @Serializable
    private data class ShareMetadataJson(
        val id: String,
        val secretId: String,
        val label: String,
        val senderKey: String,
        val recipientKey: String,
        val createdAt: String,
        val pickedUpAt: String? = null,
    )

    @Serializable
    private data class OpenShareRequestJson(
        val shareId: String,
        val requestType: String,
    )

    @Serializable
    private data class PickUpShareResponseJson(val ciphertext: String)

    @Serializable
    private data class RespondJson(val state: String, val ciphertext: String? = null)

    @Serializable
    private data class ShareRequestJson(
        val id: String,
        val share: ShareMetadataJson,
        val requestType: String,
        val state: String,
        val requestedAt: String,
        val respondedAt: String? = null,
        val ciphertext: String? = null,
    )

    // ── Domain conversions ────────────────────────────────────────────────────

    private fun ShareMetadataJson.toDomain() = ShareMetadata(
        id = UUID.fromString(id),
        secretId = UUID.fromString(secretId),
        label = label,
        senderKey = senderKey.decodeBase64Url(),
        recipientKey = recipientKey.decodeBase64Url(),
        createdAt = createdAt,
        pickedUpAt = pickedUpAt,
    )

    private fun ShareRequestJson.toDomain() = ShareRequest(
        id = UUID.fromString(id),
        share = share.toDomain(),
        requestType = when (requestType) {
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
        requestedAt = requestedAt,
        respondedAt = respondedAt,
        ciphertext = ciphertext?.decodeBase64(),
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
