package com.deposplit.value_objects

/**
 * The sender-declared media type of a secret — `"text/plain"` for everything that can be split
 * today.
 *
 * Sender-supplied and best-effort, exactly the trust level [Secret.label] already has: nothing
 * sniffs the bytes to check the claim, and the relay could not check it if it wanted to, seeing
 * only ciphertext. It rides the deposit payload and the inventory push so a holder can hand it back
 * during recovery, and so reconstruction knows how to render what it produced.
 *
 * A wrong or hostile value is a *rendering* risk, never a confidentiality one: by the time it is
 * read, k holders have already consented and the plaintext is already on this device.
 */
@JvmInline
value class MimeType(val value: String) {

    val isText: Boolean get() = essence.startsWith("text/")
    val isImage: Boolean get() = essence.startsWith("image/")

    /**
     * Parameters dropped and lowercased, so `Text/Plain; charset=utf-8` classifies as text. Only
     * classification normalises — [value] is what was signed and must stay byte-exact.
     */
    private val essence: String
        get() = value.substringBefore(';').trim().lowercase()

    companion object {
        val DEFAULT = MimeType("text/plain")
    }
}
