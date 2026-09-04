package com.deposplit.driving_adapters

import com.deposplit.driven_ports.IdentityStore
import com.deposplit.value_objects.IdentityIntegrity
import com.deposplit.value_objects.IdentityStorageUnavailableException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An IdentityStore that can be put into the states a phone switch produces: app files restored,
 * private key material gone or belonging to some other identity, or readable only once the device
 * is unlocked.
 */
private class RestorableIdentityStore : IdentityStore {
    private var registered = false
    private var _pseudonym = ""
    private var _verifyKey = ByteArray(0)
    private var _signKey = ByteArray(0)
    private var _encKey = ByteArray(0)
    private var _decKey = ByteArray(0)
    private var _previousDecKey: ByteArray? = null

    /** Thrown by every private-key read, standing in for a keystore that no longer decrypts. */
    var privateKeyFailure: Exception? = null

    override fun isRegistered() = registered

    override fun save(pseudonym: String, verifyKey: ByteArray, signKey: ByteArray, encKey: ByteArray, decKey: ByteArray) {
        this._pseudonym = pseudonym
        this._verifyKey = verifyKey
        this._signKey = signKey
        this._encKey = encKey
        this._decKey = decKey
        this._previousDecKey = null
        registered = true
    }

    override fun rotate(verifyKey: ByteArray, signKey: ByteArray, encKey: ByteArray, decKey: ByteArray) {
        this._previousDecKey = _decKey
        this._verifyKey = verifyKey
        this._signKey = signKey
        this._encKey = encKey
        this._decKey = decKey
    }

    /** Leaves the public keys where they are and swaps the private halves for another identity's. */
    fun replacePrivateKeysWith(other: RestorableIdentityStore) {
        this._signKey = other._signKey
        this._decKey = other._decKey
    }

    override fun pseudonym() = _pseudonym
    override fun verifyKey() = _verifyKey
    override fun signKey() = privateKeyFailure?.let { throw it } ?: _signKey
    override fun encKey() = _encKey
    override fun decKey() = privateKeyFailure?.let { throw it } ?: _decKey
    override fun previousDecKey() = _previousDecKey
}

class IdentityServiceIntegrityTest {

    private fun registered(): Pair<IdentityService, RestorableIdentityStore> {
        val store = RestorableIdentityStore()
        val svc = IdentityService(store)
        svc.register("test")
        return svc to store
    }

    @Test
    fun `a device that just registered is intact`() {
        val (svc, _) = registered()
        assertEquals(IdentityIntegrity.INTACT, svc.integrity())
    }

    @Test
    fun `an unregistered device is intact, having nothing to have lost`() {
        val svc = IdentityService(RestorableIdentityStore())
        assertEquals(IdentityIntegrity.INTACT, svc.integrity())
    }

    @Test
    fun `a rotation leaves the identity intact`() {
        val (svc, _) = registered()
        svc.activateKeyPair(svc.generateNewKeyPair())
        assertEquals(IdentityIntegrity.INTACT, svc.integrity())
    }

    // The restore case: app storage came across, key storage did not.
    @Test
    fun `private keys that no longer read are keys lost`() {
        val (svc, store) = registered()
        store.privateKeyFailure = IllegalStateException("keystore blob no longer decrypts")
        assertEquals(IdentityIntegrity.KEYS_LOST, svc.integrity())
    }

    // The Android restore in particular: verify_key and enc_key are stored in the clear beside the
    // wrapped private halves, so they come back intact and the device advertises keys it cannot
    // use. Nothing else in the app produces an error for this.
    @Test
    fun `public keys that outlive their private halves are keys lost`() {
        val (svc, store) = registered()
        val (_, someoneElse) = registered()
        store.replacePrivateKeysWith(someoneElse)
        assertEquals(IdentityIntegrity.KEYS_LOST, svc.integrity())
    }

    // A locked device must never be mistaken for an emptied one — KEYS_LOST is what offers to mint
    // a replacement identity over the top.
    @Test
    fun `key storage that is merely locked is unreadable, not lost`() {
        val (svc, store) = registered()
        store.privateKeyFailure = IdentityStorageUnavailableException("device is locked")
        assertEquals(IdentityIntegrity.UNREADABLE, svc.integrity())
    }
}
