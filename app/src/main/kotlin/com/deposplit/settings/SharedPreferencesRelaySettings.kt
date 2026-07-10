package com.deposplit.settings

import android.content.Context
import com.deposplit.api.RelayDefaults
import com.deposplit.driven_ports.RelaySettings

class SharedPreferencesRelaySettings(context: Context) : RelaySettings {

    private val prefs = context.getSharedPreferences("deposplit", Context.MODE_PRIVATE)

    override fun getDefaultRelayBaseUrl(): String =
        prefs.getString(KEY, null) ?: RelayDefaults.FALLBACK_BASE_URL

    override fun setDefaultRelayBaseUrl(url: String?) {
        prefs.edit().apply {
            if (url.isNullOrBlank()) remove(KEY) else putString(KEY, url)
        }.apply()
    }

    companion object {
        private const val KEY = "default_relay_base_url"
    }
}
