package com.deposplit.ui.qr

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

// `relay` carries the *displaying* device's currently-configured relay — the out-of-band exchange
// mechanism BYOR uses (deposplit.com/CLAUDE.md "BYOR"). Absent means "use the scanning device's
// own default relay". Compatible both directions regardless of the `v` bump: kotlinx.serialization
// treats a missing/nullable field as null on decode, and old (v=1) readers ignore the extra field.
@Serializable
data class QrPayload(
    val v: Int,
    val pseudonym: String,
    val ed: String,
    val x: String,
    val relay: String? = null,
)

private val json = Json { ignoreUnknownKeys = true }
private val b64Url = Base64.getUrlEncoder().withoutPadding()

fun encodeQrPayload(pseudonym: String, edPublicKey: ByteArray, xPublicKey: ByteArray, relayBaseUrl: String? = null): String =
    json.encodeToString(
        QrPayload(
            v = 2,
            pseudonym = pseudonym,
            ed = b64Url.encodeToString(edPublicKey),
            x = b64Url.encodeToString(xPublicKey),
            relay = relayBaseUrl,
        )
    )

fun decodeQrPayload(raw: String): QrPayload = json.decodeFromString(raw)
