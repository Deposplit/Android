package com.deposplit

import android.app.Application
import com.deposplit.api.DeposplitApiAdapter
import com.deposplit.auth.DeposplitAuthAdapter
import com.deposplit.contacts.LocalContactRepository

class DeposplitApp : Application() {

    lateinit var authAdapter: DeposplitAuthAdapter
        private set

    lateinit var shareTransport: DeposplitApiAdapter
        private set

    lateinit var contactRepository: LocalContactRepository
        private set

    override fun onCreate() {
        super.onCreate()
        authAdapter = DeposplitAuthAdapter(this)
        shareTransport = DeposplitApiAdapter(
            auth = authAdapter,
            baseUrl = if (BuildConfig.DEBUG) "http://10.0.2.2:9000/v1" else "https://api.deposplit.com/v1",
        )
        contactRepository = LocalContactRepository(this)
    }
}
