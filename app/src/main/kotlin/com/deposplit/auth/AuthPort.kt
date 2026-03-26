package com.deposplit.auth

sealed interface LoginFlow {
    data class Oidc(val authorizationUrl: String) : LoginFlow
    data object Password : LoginFlow
}

interface AuthPort {
    suspend fun discoverLoginFlow(homeserverUrl: String): LoginFlow
    suspend fun completeOidcLogin(callbackUrl: String)
    suspend fun loginWithPassword(homeserverUrl: String, username: String, password: String)
    fun isLoggedIn(): Boolean
    fun clearSession()
}
