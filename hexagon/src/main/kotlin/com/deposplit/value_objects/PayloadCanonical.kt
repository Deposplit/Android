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

    /**
     * Signed by the sender when opening a share request (`senderSignature`).
     *
     * `k`/`n` (item 8) are appended at the end of the sequence, keeping the existing field order
     * — and this construction's cross-platform byte-vector test — undisturbed.
     */
    fun forOpen(
        secretId: UUID,
        transactionType: ShareTransactionType,
        recipientKey: ByteArray,
        label: String,
        secretCreatedAt: Instant,
        shareId: UUID?,
        ciphertext: ByteArray?,
        k: Int? = null,
        n: Int? = null,
    ): ByteArray = listOf(
        secretId.toString(),
        transactionType.wireValue,
        base64Url.encodeToString(recipientKey),
        label,
        secretCreatedAt.toEpochMilli().toString(),
        shareId?.toString() ?: "",
        ciphertext?.let { base64Std.encodeToString(it) } ?: "",
        k?.toString() ?: "",
        n?.toString() ?: "",
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    /** Signed by the recipient when responding to a share request (`recipientSignature`). */
    fun forRespond(requestId: UUID, approved: Boolean, ciphertext: ByteArray?): ByteArray = listOf(
        requestId.toString(),
        if (approved) "approved" else "denied",
        ciphertext?.let { base64Std.encodeToString(it) } ?: "",
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    /**
     * Signed by the old key when pushing a rotation notice (item 9), i.e. by the caller who
     * becomes [KeyRotation.oldEd25519Key]. Proves continuity of key control — only someone
     * holding the old private key can produce this signature, which is what lets the recipient
     * auto-verify and auto-accept the rotation without a fresh human re-verification.
     */
    fun forRotation(recipientKey: ByteArray, newEd25519Key: ByteArray, newX25519Key: ByteArray): ByteArray = listOf(
        base64Url.encodeToString(recipientKey),
        base64Url.encodeToString(newEd25519Key),
        base64Url.encodeToString(newX25519Key),
    ).joinToString("\n").toByteArray(Charsets.UTF_8)
}
