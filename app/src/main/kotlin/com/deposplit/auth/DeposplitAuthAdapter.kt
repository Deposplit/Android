package com.deposplit.auth

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class DeposplitAuthAdapter(context: Context) : AuthPort {

    private val prefs = context.getSharedPreferences("deposplit", Context.MODE_PRIVATE)

    override fun isRegistered(): Boolean = prefs.getBoolean("registered", false)

    override fun register(pseudonym: String) {
        val random = SecureRandom()

        val edGen = Ed25519KeyPairGenerator()
        edGen.init(Ed25519KeyGenerationParameters(random))
        val edPair = edGen.generateKeyPair()
        val edPk = (edPair.public as Ed25519PublicKeyParameters).encoded
        val edSk = (edPair.private as Ed25519PrivateKeyParameters).encoded

        val xGen = X25519KeyPairGenerator()
        xGen.init(X25519KeyGenerationParameters(random))
        val xPair = xGen.generateKeyPair()
        val xPk = (xPair.public as X25519PublicKeyParameters).encoded
        val xSk = (xPair.private as X25519PrivateKeyParameters).encoded

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

    override fun xPublicKey(): ByteArray = requirePref("x_pk").decodeBase64()

    override fun sign(message: ByteArray): ByteArray {
        val sk = Ed25519PrivateKeyParameters(prefs.getDecrypted(loadOrCreateMasterKey(), "ed_sk"))
        val signer = Ed25519Signer()
        signer.init(true, sk)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    override fun encrypt(plaintext: ByteArray, recipientXPublicKey: ByteArray): ByteArray {
        val sk = X25519PrivateKeyParameters(prefs.getDecrypted(loadOrCreateMasterKey(), "x_sk"))
        val nonce = ByteArray(NONCE_BYTES).also { secureRandom.nextBytes(it) }
        val key = deriveKey(sk, X25519PublicKeyParameters(recipientXPublicKey), nonce)
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), TAG_BITS, nonce))
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        var len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        len += cipher.doFinal(out, len)
        return nonce + out.copyOf(len)
    }

    override fun decrypt(noncePlusCiphertext: ByteArray, recipientXPublicKey: ByteArray): ByteArray {
        val sk = X25519PrivateKeyParameters(prefs.getDecrypted(loadOrCreateMasterKey(), "x_sk"))
        val nonce = noncePlusCiphertext.copyOfRange(0, NONCE_BYTES)
        val ciphertext = noncePlusCiphertext.copyOfRange(NONCE_BYTES, noncePlusCiphertext.size)
        val key = deriveKey(sk, X25519PublicKeyParameters(recipientXPublicKey), nonce)
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), TAG_BITS, nonce))
        val out = ByteArray(cipher.getOutputSize(ciphertext.size))
        var len = cipher.processBytes(ciphertext, 0, ciphertext.size, out, 0)
        len += cipher.doFinal(out, len)
        return out.copyOf(len)
    }

    private fun deriveKey(
        sk: X25519PrivateKeyParameters,
        pk: X25519PublicKeyParameters,
        nonce: ByteArray,
    ): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(sk)
        val sharedSecret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(pk, sharedSecret, 0)
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(sharedSecret, nonce, HKDF_INFO))
        val key = ByteArray(KEY_BYTES)
        hkdf.generateBytes(key, 0, KEY_BYTES)
        return key
    }

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
        private const val NONCE_BYTES = 12
        private const val KEY_BYTES = 32
        private const val TAG_BITS = 128
        private val HKDF_INFO = "deposplit-share".toByteArray(Charsets.UTF_8)
        private val secureRandom = SecureRandom()
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
