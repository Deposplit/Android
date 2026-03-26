package com.deposplit.auth

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.OidcConfiguration
import org.matrix.rustcomponents.sdk.OidcPrompt
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import uniffi.matrix_sdk.OAuthAuthorizationData

// NOTE: Targets matrix-rust-sdk 26.03.24. The old AuthenticationService-based API was
// removed — auth methods now live directly on Client. If anything fails to resolve after
// a Gradle sync, check the generated bindings at org.matrix.rustcomponents.sdk in the
// External Libraries section of the Android Studio project view.

private const val OIDC_REDIRECT_URI = "deposplit://auth/callback"
private const val PREFS_NAME = "deposplit_prefs"
private const val KEY_LOGGED_IN = "is_logged_in"

class MatrixAuthAdapter(private val context: Context) : AuthPort {

    private val sessionDataDir = context.filesDir.resolve("matrix/session")
    private val sessionCacheDir = context.cacheDir.resolve("matrix/session")

    // Plain SharedPreferences is fine here — the sensitive session data (tokens, keys)
    // is stored by the SDK itself in its encrypted SQLite database under sessionDataDir.
    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Held between discoverLoginFlow() and completeOidcLogin() — must be the same Client
    // instance for the OIDC exchange to succeed.
    private var pendingClient: Client? = null
    private var pendingOidcData: OAuthAuthorizationData? = null  // AutoCloseable — always close

    private var activeClient: Client? = null

    private fun oidcConfiguration() = OidcConfiguration(
        clientName = "Deposplit",
        redirectUri = OIDC_REDIRECT_URI,
        clientUri = "https://deposplit.com",
        logoUri = null,
        tosUri = null,
        policyUri = null,
        staticRegistrations = emptyMap(),
    )

    private suspend fun buildClient(homeserverUrl: String): Client {
        sessionDataDir.mkdirs()
        sessionCacheDir.mkdirs()
        return ClientBuilder()
            .serverNameOrHomeserverUrl(homeserverUrl)
            .sessionPaths(sessionDataDir.absolutePath, sessionCacheDir.absolutePath)
            .userAgent("Deposplit/1.0")
            .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.DISCOVER_NATIVE)
            .build()
    }

    override suspend fun discoverLoginFlow(homeserverUrl: String): LoginFlow =
        withContext(Dispatchers.IO) {
            val client = buildClient(homeserverUrl)
            pendingClient = client

            val details = client.homeserverLoginDetails()
            val supportsOidc = details.supportsOidcLogin()
            details.close()

            if (supportsOidc) {
                // Element X uses OidcPrompt.Unknown("consent") rather than OidcPrompt.Login —
                // it triggers the consent screen that most homeservers expect.
                val oidcData = client.urlForOidc(
                    oidcConfiguration = oidcConfiguration(),
                    prompt = OidcPrompt.Unknown("consent"),
                    loginHint = null,
                    deviceId = null,
                    additionalScopes = emptyList(),
                )
                pendingOidcData = oidcData
                LoginFlow.Oidc(oidcData.loginUrl())
            } else {
                LoginFlow.Password
            }
        }

    override suspend fun completeOidcLogin(callbackUrl: String): Unit =
        withContext(Dispatchers.IO) {
            val client = checkNotNull(pendingClient) { "Call discoverLoginFlow first" }
            val oidcData = checkNotNull(pendingOidcData) { "No pending OIDC login" }
            try {
                client.loginWithOidcCallback(callbackUrl = callbackUrl)
                activeClient = client
                pendingClient = null
                prefs.edit { putBoolean(KEY_LOGGED_IN, true) }
            } finally {
                // OAuthAuthorizationData must always be closed, success or failure.
                oidcData.close()
                pendingOidcData = null
            }
        }

    override suspend fun loginWithPassword(
        homeserverUrl: String,
        username: String,
        password: String,
    ): Unit = withContext(Dispatchers.IO) {
        val client = pendingClient ?: buildClient(homeserverUrl)
        client.login(
            username = username,
            password = password,
            initialDeviceName = "Deposplit Android",
            deviceId = null,
        )
        activeClient = client
        pendingClient = null
        prefs.edit { putBoolean(KEY_LOGGED_IN, true) }
    }

    override fun isLoggedIn(): Boolean = prefs.getBoolean(KEY_LOGGED_IN, false)

    override fun clearSession() {
        activeClient = null
        pendingClient = null
        pendingOidcData?.close()
        pendingOidcData = null
        prefs.edit { remove(KEY_LOGGED_IN) }
    }
}
