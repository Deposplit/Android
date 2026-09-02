package com.deposplit.value_objects

/**
 * The sender-declared media type of a secret — `"text/plain"` for typed text, `"image/png"` or
 * `"image/jpeg"` for a picked image.
 *
 * Best-effort in general, at exactly the trust level [Secret.label] already has: nothing on a
 * receiving device checks the claim against the bytes, and the relay could not check it either,
 * seeing only ciphertext. For a secret *this* device splits the claim is nevertheless true by
 * construction, because [MimeType.sniffed] reads it off the payload rather than believing whatever
 * handed the bytes over. It rides the deposit payload and the inventory push so a holder can hand
 * it back during recovery, and so reconstruction knows how to render what it produced.
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
        val PNG = MimeType("image/png")
        val JPEG = MimeType("image/jpeg")

        private val PNG_MAGIC =
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

        /**
         * The image type these bytes actually are, or `null` for anything else.
         *
         * The accepted set is PNG and JPEG deliberately, and is the whole of it: every additional
         * format is more decoder surface reached by attacker-chosen bytes, for a use case nobody
         * has asked for yet. SVG in particular is scriptable and will not be added.
         *
         * Recognition is by leading bytes, never by a file name, a content-resolver type, or what a
         * picker claimed, so the declared type of a secret this device splits cannot disagree with
         * its payload.
         */
        fun sniffed(bytes: ByteArray): MimeType? = when {
            bytes.startsWith(PNG_MAGIC) -> PNG
            bytes.startsWith(JPEG_MAGIC) -> JPEG
            else -> null
        }

        private fun ByteArray.startsWith(magic: ByteArray): Boolean =
            size >= magic.size && magic.indices.all { this[it] == magic[it] }
    }
}
