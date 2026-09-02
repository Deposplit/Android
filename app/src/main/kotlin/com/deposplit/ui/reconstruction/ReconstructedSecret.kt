package com.deposplit.ui.reconstruction

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import com.deposplit.value_objects.MimeType
import java.nio.ByteBuffer
import java.text.CharacterIterator
import java.text.StringCharacterIterator

/**
 * How a reconstructed secret should be shown, decided once from the declared type *and* the bytes
 * themselves so every reconstruct site agrees and none has to re-derive it.
 *
 * The classification is deliberately fail-safe. A declared type is only ever a claim — nothing
 * verified it against the payload, and nothing could, since the relay only ever saw ciphertext — so
 * every branch that could fail falls through to [Binary] rather than erroring or guessing. That is
 * also why a payload declared as text is decoded strictly instead of with `toString(UTF_8)`: the lossy
 * decode this replaces silently substituted U+FFFD and threw the real bytes away.
 *
 * A wrong or hostile type is therefore a rendering matter, never a confidentiality one. By the time
 * it is read, k holders have already consented and the plaintext is already on this device.
 */
sealed interface ReconstructedSecret {
    data class Text(val text: String) : ReconstructedSecret
    /**
     * Carries the payload beside the decoded bitmap, because the decoded bitmap is *not* the
     * secret: re-encoding it would hand back different bytes under the original type's name. Export
     * uses [bytes]; only the display uses the [bitmap].
     */
    data class Image(val bitmap: Bitmap, val bytes: ByteArray) : ReconstructedSecret {
        override fun equals(other: Any?) = other is Image && bitmap == other.bitmap && bytes.contentEquals(other.bytes)
        override fun hashCode() = 31 * bitmap.hashCode() + bytes.contentHashCode()
    }
    data class Binary(val bytes: ByteArray) : ReconstructedSecret {
        override fun equals(other: Any?) = other is Binary && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
    }

    companion object {
        fun of(secret: ByteArray, mimeType: MimeType): ReconstructedSecret {
            if (mimeType.isText) {
                secret.decodeToStringOrNull()?.let { return Text(it) }
            }
            if (mimeType.isImage) {
                secret.decodeBitmapOrNull()?.let { return Image(it, secret) }
            }
            return Binary(secret)
        }
    }
}

/** Strict UTF-8 decode: null rather than U+FFFD, so a caller can tell "not text" from "text". */
private fun ByteArray.decodeToStringOrNull(): String? =
    runCatching { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(this)).toString() }.getOrNull()

/**
 * The platform's own decoder, so attacker-chosen bytes never reach a bundled image library — the
 * one real risk a bad mimeType creates. It throws on malformed input, which is the fall-through
 * this relies on.
 */
private fun ByteArray.decodeBitmapOrNull(): Bitmap? = runCatching {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(this))) { decoder, _, _ ->
        // Mutable-and-software so the result can be drawn by Compose without a hardware-buffer
        // round trip, which a HARDWARE bitmap would otherwise force.
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
}.getOrNull()

/** Human-readable byte count, for the binary view and the repair form's carried-through payload. */
fun formatByteCount(bytes: Int): String = formatByteCount(bytes.toLong())

/** Long overload: a picker reports a file length, which can exceed what an Int would hold. */
fun formatByteCount(bytes: Long): String {
    var value = bytes
    if (-1000 < value && value < 1000) return "$value B"
    val units: CharacterIterator = StringCharacterIterator("kMGTPE")
    while (value <= -999_950 || value >= 999_950) {
        value /= 1000
        units.next()
    }
    return String.format(java.util.Locale.ROOT, "%.1f %cB", value / 1000.0, units.current())
}
