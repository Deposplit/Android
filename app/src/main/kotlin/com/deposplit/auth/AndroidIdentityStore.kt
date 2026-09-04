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

    override fun save(pseudonym: String, verifyKey: ByteArray, signKey: ByteArray, encKey: ByteArray, decKey: ByteArray) {
        val masterKey = loadOrCreateMasterKey()
        prefs.edit()
            .putString("pseudonym", pseudonym)
            .putString("verify_key", verifyKey.encodeBase64())
            .putEncrypted(masterKey, "sign_key", signKey)
            .putString("enc_key", encKey.encodeBase64())
            .putEncrypted(masterKey, "dec_key", decKey)
            .remove("previous_dec_key")
            .remove("previous_dec_key_iv")
            .putBoolean("registered", true)
            .apply()
    }

    // One commit, so the displaced key and the new one can never disagree about which generation
    // is current.
    override fun rotate(verifyKey: ByteArray, signKey: ByteArray, encKey: ByteArray, decKey: ByteArray) {
        val masterKey = loadOrCreateMasterKey()
        val displaced = runCatching { prefs.getDecrypted(masterKey, "dec_key") }.getOrNull()
        val editor = prefs.edit()
            .putString("verify_key", verifyKey.encodeBase64())
            .putEncrypted(masterKey, "sign_key", signKey)
            .putString("enc_key", encKey.encodeBase64())
            .putEncrypted(masterKey, "dec_key", decKey)
        if (displaced == null) {
            editor.remove("previous_dec_key").remove("previous_dec_key_iv")
        } else {
            editor.putEncrypted(masterKey, "previous_dec_key", displaced)
        }
        editor.apply()
    }

    override fun previousDecKey(): ByteArray? =
        runCatching { prefs.getDecrypted(requireMasterKey(), "previous_dec_key") }.getOrNull()

    override fun pseudonym(): String = requirePref("pseudonym")

    override fun verifyKey(): ByteArray? = prefs.getString("verify_key", null)?.decodeBase64()

    override fun signKey(): ByteArray = prefs.getDecrypted(requireMasterKey(), "sign_key")

    override fun encKey(): ByteArray? = prefs.getString("enc_key", null)?.decodeBase64()

    override fun decKey(): ByteArray = prefs.getDecrypted(requireMasterKey(), "dec_key")

    private fun requirePref(key: String): String =
        prefs.getString(key, null) ?: error("Not registered — '$key' missing")

    private fun loadMasterKey(): SecretKey? {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        return if (keyStore.containsAlias(KEYSTORE_ALIAS)) keyStore.getKey(KEYSTORE_ALIAS, null) as SecretKey else null
    }

    /* Reads must never create. A restored device has the wrapped key material in its preferences
     * and no alias to unwrap it with; minting one here would hand every read a key that cannot
     * decrypt anything, and leave a stray alias behind. Absence is the answer the caller needs.
     *
     * There is no Android equivalent of a locked keystore to report as temporarily unreadable: the
     * master key is not bound to user authentication, so it is available whenever the app runs.
     * That is why nothing here throws IdentityStorageUnavailableException. */
    private fun requireMasterKey(): SecretKey =
        loadMasterKey() ?: error("Key storage holds no '$KEYSTORE_ALIAS' — this device's private keys are gone")

    private fun loadOrCreateMasterKey(): SecretKey {
        loadMasterKey()?.let { return it }
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
