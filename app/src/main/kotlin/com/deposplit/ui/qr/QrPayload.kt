package com.deposplit.ui.qr

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

@Serializable
data class QrPayload(
    val v: Int,
    val pseudonym: String,
    val ed: String,
    val x: String,
)

private val json = Json { ignoreUnknownKeys = true }
private val b64Url = Base64.getUrlEncoder().withoutPadding()

fun encodeQrPayload(pseudonym: String, edPublicKey: ByteArray, xPublicKey: ByteArray): String =
    json.encodeToString(
        QrPayload(
            v = 1,
            pseudonym = pseudonym,
            ed = b64Url.encodeToString(edPublicKey),
            x = b64Url.encodeToString(xPublicKey),
        )
    )

fun decodeQrPayload(raw: String): QrPayload = json.decodeFromString(raw)
