package com.deposplit.value_objects

import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Canonical byte constructions for the two payload-level signatures that ride with a
 * ShareRequest row (`senderSignature`, `recipientSignature`), independent of the per-call
 * transport-auth signature. Mirrors deposplit.com's `hexagons/relay` PayloadCanonical
 * byte-for-byte — keep both in sync.
 *
 * The transport signature authenticates the HTTP caller for one specific call and is never
 * persisted, so it gives a later reader of a row nothing to re-verify authorship against. These
 * two signatures close that gap, which is what makes BYOR (a relay other than deposplit.com)
 * safe: any holder of the author's Ed25519 public key can independently re-verify who authored a
 * row, regardless of which relay served it.
 *
 * `secretCreatedAt` is signed as epoch milliseconds (not the ISO-8601 wire string) and UUIDs are
 * signed lowercase (`UUID.toString()`'s default) — both choices exist purely to keep the signed
 * bytes byte-identical across the JVM, Kotlin, and Swift implementations.
 */
object PayloadCanonical {

    private val base64Std: Base64.Encoder = Base64.getEncoder()
    private val base64Url: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    private fun ShareRequestType.toWire(): String = when (this) {
        ShareRequestType.PICK_UP -> "pick_up"
        ShareRequestType.RETRIEVE -> "retrieve"
        ShareRequestType.DELETE -> "delete"
    }

    /** Signed by the sender when opening a share request (`senderSignature`). */
    fun forOpen(
        secretId: UUID,
        requestType: ShareRequestType,
        recipientKey: ByteArray,
        label: String,
        secretCreatedAt: Instant,
        shareId: UUID?,
        ciphertext: ByteArray?,
    ): ByteArray = listOf(
        secretId.toString(),
        requestType.toWire(),
        base64Url.encodeToString(recipientKey),
        label,
        secretCreatedAt.toEpochMilli().toString(),
        shareId?.toString() ?: "",
        ciphertext?.let { base64Std.encodeToString(it) } ?: "",
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    /** Signed by the recipient when responding to a share request (`recipientSignature`). */
    fun forRespond(requestId: UUID, approved: Boolean, ciphertext: ByteArray?): ByteArray = listOf(
        requestId.toString(),
        if (approved) "approved" else "denied",
        ciphertext?.let { base64Std.encodeToString(it) } ?: "",
    ).joinToString("\n").toByteArray(Charsets.UTF_8)
}
