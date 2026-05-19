package com.deposplit.auth

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.deposplit.driven_ports.IdentityStore
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidIdentityStore(context: Context) : IdentityStore {

    private val prefs = context.getSharedPreferences("deposplit", Context.MODE_PRIVATE)

    override fun isRegistered(): Boolean = prefs.getBoolean("registered", false)

    override fun save(pseudonym: String, edPk: ByteArray, edSk: ByteArray, xPk: ByteArray, xSk: ByteArray) {
        val masterKey = loadOrCreateMasterKey()
        prefs.edit()
            .putString("pseudonym", pseudonym)
            .putString("ed_pk", edPk.encodeBase64())
            .putEncrypted(masterKey, "ed_sk", edSk)
            .putString("x_pk", xPk.encodeBase64())
            .putEncrypted(masterKey, "x_sk", xSk)
            .putBoolean("registered", true)
            .apply()
    }

    override fun pseudonym(): String = requirePref("pseudonym")

    override fun edPublicKey(): ByteArray = requirePref("ed_pk").decodeBase64()

    override fun edPrivateKey(): ByteArray = prefs.getDecrypted(loadOrCreateMasterKey(), "ed_sk")

    override fun xPublicKey(): ByteArray = requirePref("x_pk").decodeBase64()

    override fun xPrivateKey(): ByteArray = prefs.getDecrypted(loadOrCreateMasterKey(), "x_sk")

    private fun requirePref(key: String): String =
        prefs.getString(key, null) ?: error("Not registered — '$key' missing")

    private fun loadOrCreateMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            return keyStore.getKey(KEYSTORE_ALIAS, null) as SecretKey
        }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    companion object {
        private const val KEYSTORE_ALIAS = "deposplit_master"
    }
}

private fun SharedPreferences.Editor.putEncrypted(
    masterKey: SecretKey,
    key: String,
    value: ByteArray,
): SharedPreferences.Editor {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, masterKey)
    putString("${key}_iv", cipher.iv.encodeBase64())
    putString(key, cipher.doFinal(value).encodeBase64())
    return this
}

private fun SharedPreferences.getDecrypted(masterKey: SecretKey, key: String): ByteArray {
    val iv = requireNotNull(getString("${key}_iv", null)) { "'${key}_iv' missing" }.decodeBase64()
    val ct = requireNotNull(getString(key, null)) { "'$key' missing" }.decodeBase64()
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(128, iv))
    return cipher.doFinal(ct)
}

private fun ByteArray.encodeBase64(): String = Base64.getEncoder().encodeToString(this)
private fun String.decodeBase64(): ByteArray = Base64.getDecoder().decode(this)
