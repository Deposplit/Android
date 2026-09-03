package com.deposplit.purchases

import android.content.Context
import com.deposplit.BuildConfig
import com.deposplit.driven_ports.PurchaseRepository

/**
 * The entitlement as this device last saw it, cached in the same "deposplit" preferences file as
 * the default relay. Nothing writes [KEY] yet: Google Play Billing cannot run until Deposplit has a
 * Play Console entry, so today the only way to a premium build is [BuildConfig.FAKE_PREMIUM]. When
 * the Billing adapter lands it has to write this one preference and nothing else moves.
 *
 * FAKE_PREMIUM is hard-coded false in the release build type, exactly as SKIP_BIOMETRIC is.
 */
class SharedPreferencesPurchaseRepository(context: Context) : PurchaseRepository {

    private val prefs = context.getSharedPreferences("deposplit", Context.MODE_PRIVATE)

    override fun isPremium(): Boolean = BuildConfig.FAKE_PREMIUM || prefs.getBoolean(KEY, false)

    fun setPremium(premium: Boolean) {
        prefs.edit().putBoolean(KEY, premium).apply()
    }

    companion object {
        private const val KEY = "premium_unlocked"
    }
}
