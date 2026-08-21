package com.deposplit.ui.qr

import com.deposplit.value_objects.CipherSuite
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

// `relay` carries the *displaying* device's currently-configured relay — the out-of-band exchange
// mechanism BYOR uses (deposplit.com/CLAUDE.md "BYOR"). Absent means "use the scanning device's
// own default relay". `cipherSuite` (item 14 — "crypto agility") is required, not optional like
// `relay`: every contact-exchange has exactly one cipher suite in effect.
//
// `v` stays at 1 permanently — Deposplit is pre-launch and never supports decoding an old shape,
// so a version number never actually gates anything: a payload missing a newly-required field
// (like `cipherSuite` here) already fails to decode on its own, regardless of what `v` says.
// Bumping `v` on every field addition would be version-tracking ceremony with no compatibility
// matrix behind it to justify it.
@Serializable
data class QrPayload(
    val v: Int,
    val pseudonym: String,
    val verifyKey: String,
    val encKey: String,
    val relay: String? = null,
    val cipherSuite: String,
)

private val json = Json { ignoreUnknownKeys = true }
private val b64Url = Base64.getUrlEncoder().withoutPadding()

fun encodeQrPayload(pseudonym: String, verifyKey: ByteArray, encKey: ByteArray, relayBaseUrl: String? = null, cipherSuite: CipherSuite = CipherSuite.current): String =
    json.encodeToString(
        QrPayload(
            v = 1,
            pseudonym = pseudonym,
            verifyKey = b64Url.encodeToString(verifyKey),
            encKey = b64Url.encodeToString(encKey),
            relay = relayBaseUrl,
            cipherSuite = cipherSuite.wireValue,
        )
    )

fun decodeQrPayload(raw: String): QrPayload = json.decodeFromString(raw)
