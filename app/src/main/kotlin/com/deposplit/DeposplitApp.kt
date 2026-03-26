package com.deposplit

import android.app.Application
import com.deposplit.auth.MatrixAuthAdapter
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class DeposplitApp : Application() {

    lateinit var authAdapter: MatrixAuthAdapter
        private set

    private val _oidcCallbackFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val oidcCallbackFlow: SharedFlow<String> = _oidcCallbackFlow.asSharedFlow()

    override fun onCreate() {
        super.onCreate()
        authAdapter = MatrixAuthAdapter(this)
    }

    fun onOidcCallback(callbackUrl: String) {
        _oidcCallbackFlow.tryEmit(callbackUrl)
    }
}
