package com.deposplit

import android.app.Application
import com.deposplit.api.DeposplitApiAdapter
import com.deposplit.auth.AndroidIdentityStore
import com.deposplit.contacts.LocalContactRepository
import com.deposplit.driving_ports.AuthPort
import com.deposplit.services.AuthService
import com.deposplit.shares.LocalShareRepository

class DeposplitApp : Application() {

    lateinit var authAdapter: AuthPort
        private set

    lateinit var shareTransport: DeposplitApiAdapter
        private set

    lateinit var contactRepository: LocalContactRepository
        private set

    lateinit var shareRepository: LocalShareRepository
        private set

    override fun onCreate() {
        super.onCreate()
        authAdapter = AuthService(AndroidIdentityStore(this))
        shareTransport = DeposplitApiAdapter(
            auth = authAdapter,
            baseUrl = BuildConfig.BASE_URL,
        )
        contactRepository = LocalContactRepository(this)
        shareRepository = LocalShareRepository(this)
    }
}
