package com.deposplit

import android.app.Application
import com.deposplit.api.DeposplitApiAdapter
import com.deposplit.auth.AndroidIdentityStore
import com.deposplit.contacts.LocalContactRepository
import com.deposplit.driving_ports.ContactManagement
import com.deposplit.driving_ports.Identity
import com.deposplit.driving_ports.ShareManagement
import com.deposplit.driving_adapters.ContactService
import com.deposplit.driving_adapters.IdentityService
import com.deposplit.driving_adapters.ShareService
import com.deposplit.shares.LocalShareMetadataRepository
import com.deposplit.shares.LocalShareRepository

class DeposplitApp : Application() {

    lateinit var authAdapter: Identity
        private set

    lateinit var contactManagement: ContactManagement
        private set

    lateinit var shareManagement: ShareManagement
        private set

    override fun onCreate() {
        super.onCreate()
        val identityService = IdentityService(AndroidIdentityStore(this))
        authAdapter = identityService
        val contactRepository = LocalContactRepository(this)
        val shareRepository = LocalShareRepository(this)
        val shareMetadataRepository = LocalShareMetadataRepository(this)
        contactManagement = ContactService(contactRepository)
        shareManagement = ShareService(
            relay = DeposplitApiAdapter(auth = identityService, baseUrl = BuildConfig.BASE_URL),
            encryption = identityService,
            shareRepository = shareRepository,
            shareMetadataRepository = shareMetadataRepository,
            contactRepository = contactRepository,
        )
    }
}
