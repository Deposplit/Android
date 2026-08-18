package com.deposplit

import android.app.Application
import com.deposplit.api.DeposplitRelayResolver
import com.deposplit.auth.AndroidIdentityStore
import com.deposplit.contacts.LocalContactRepository
import com.deposplit.driven_ports.RelaySettings
import com.deposplit.driving_ports.CatalogManagement
import com.deposplit.driving_ports.ContactManagement
import com.deposplit.driving_ports.Identity
import com.deposplit.driving_ports.ShareManagement
import com.deposplit.driving_adapters.CatalogService
import com.deposplit.driving_adapters.ContactService
import com.deposplit.driving_adapters.IdentityService
import com.deposplit.driving_adapters.ShareService
import com.deposplit.settings.SharedPreferencesRelaySettings
import com.deposplit.shares.LocalSecretRepository
import com.deposplit.shares.LocalShareMetadataRepository
import com.deposplit.shares.LocalShareRepository

class DeposplitApp : Application() {

    lateinit var authAdapter: Identity
        private set

    lateinit var contactManagement: ContactManagement
        private set

    lateinit var shareManagement: ShareManagement
        private set

    lateinit var catalogManagement: CatalogManagement
        private set

    lateinit var relaySettings: RelaySettings
        private set

    override fun onCreate() {
        super.onCreate()
        val identityService = IdentityService(AndroidIdentityStore(this))
        authAdapter = identityService
        relaySettings = SharedPreferencesRelaySettings(this)
        val contactRepository = LocalContactRepository(this)
        val shareRepository = LocalShareRepository(this)
        val shareMetadataRepository = LocalShareMetadataRepository(this)
        val secretRepository = LocalSecretRepository(this)
        contactManagement = ContactService(contactRepository)
        shareManagement = ShareService(
            relayResolver = DeposplitRelayResolver(auth = identityService, relaySettings = relaySettings),
            encryption = identityService,
            shareRepository = shareRepository,
            shareMetadataRepository = shareMetadataRepository,
            secretRepository = secretRepository,
            contactRepository = contactRepository,
            contactManagement = contactManagement,
            identity = identityService,
        )
        catalogManagement = CatalogService(
            contactRepository = contactRepository,
            secretRepository = secretRepository,
            shareMetadataRepository = shareMetadataRepository,
        )
    }
}
