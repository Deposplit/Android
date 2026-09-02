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
     * `k`/`n`, then `mimeType`, are each appended at the end of the sequence in turn, keeping the
     * field order that predates them — and this construction's cross-platform byte-vector test —
     * undisturbed.
     *
     * A null and an empty-string `mimeType` produce identical bytes here, which is why the relay
     * refuses to store an empty one.
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
        mimeType: MimeType? = null,
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
        mimeType?.value ?: "",
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    /** Signed by the recipient when responding to a share request (`recipientSignature`). */
    fun forRespond(requestId: UUID, approved: Boolean, ciphertext: ByteArray?): ByteArray = listOf(
        requestId.toString(),
        if (approved) "approved" else "denied",
        ciphertext?.let { base64Std.encodeToString(it) } ?: "",
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    /**
     * Signed by the old key when pushing a rotation notice, i.e. by the caller who
     * becomes [KeyRotation.oldVerifyKey]. Proves continuity of key control — only someone
     * holding the old private key can produce this signature, which is what lets the recipient
     * auto-verify and auto-accept the rotation without a fresh human re-verification.
     *
     * [newCipherSuite] is appended at the end of the sequence, keeping the field order that
     * predates cipher suites — and this construction's cross-platform byte-vector test —
     * undisturbed. No `oldCipherSuite` is signed — the recipient already has it pinned on the
     * existing contact record.
     */
    fun forRotation(recipientKey: ByteArray, newVerifyKey: ByteArray, newEncKey: ByteArray, newCipherSuite: CipherSuite): ByteArray = listOf(
        base64Url.encodeToString(recipientKey),
        base64Url.encodeToString(newVerifyKey),
        base64Url.encodeToString(newEncKey),
        newCipherSuite.wireValue,
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    /**
     * Signed by the holder when pushing a custodial-heartbeat push, i.e. by the caller
     * who becomes [CustodyHeartbeat.holderKey]. [secretIds] is sorted (lowercase `UUID.toString`)
     * before joining so the signed bytes are independent of list-construction order on either
     * side. The same construction covers the opt-out notice ([optedOut] `= true`, `secretIds`
     * typically empty) — mechanically the same signed row, just a different meaning to the reader.
     */
    fun forHeartbeat(ownerKey: ByteArray, secretIds: List<UUID>, optedOut: Boolean): ByteArray = listOf(
        base64Url.encodeToString(ownerKey),
        secretIds.map { it.toString() }.sorted().joinToString(","),
        optedOut.toString(),
    ).joinToString("\n").toByteArray(Charsets.UTF_8)
}
