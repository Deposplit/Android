package com.deposplit.api

/**
 * Fallback used until the user configures a different default relay via Settings — fully
 * decoupled from build-variant machinery, unlike the old `BuildConfig.BASE_URL`.
 */
object RelayDefaults {
    const val FALLBACK_BASE_URL = "https://api.deposplit.com"
}
