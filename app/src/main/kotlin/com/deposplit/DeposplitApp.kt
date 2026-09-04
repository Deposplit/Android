package com.deposplit

import android.app.Application
import com.deposplit.api.DeposplitRelayResolver
import com.deposplit.auth.AndroidIdentityStore
import com.deposplit.contacts.LocalContactRelinkRepository
import com.deposplit.contacts.LocalContactRepository
import com.deposplit.contacts.LocalKeyConflictRepository
import com.deposplit.driven_ports.PurchaseRepository
import com.deposplit.driven_ports.RelaySettings
import com.deposplit.driving_ports.CatalogManagement
import com.deposplit.driving_ports.ContactManagement
import com.deposplit.driving_ports.Identity
import com.deposplit.driving_ports.ShareManagement
import com.deposplit.driving_adapters.CatalogService
import com.deposplit.driving_adapters.ContactService
import com.deposplit.driving_adapters.IdentityService
import com.deposplit.driving_adapters.ShareService
import com.deposplit.purchases.SharedPreferencesPurchaseRepository
import com.deposplit.settings.SharedPreferencesRelaySettings
import com.deposplit.shares.LocalRetainedDepositRepository
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

    lateinit var purchases: PurchaseRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val identityStore = AndroidIdentityStore(this)
        val identityService = IdentityService(identityStore)
        authAdapter = identityService
        relaySettings = SharedPreferencesRelaySettings(this)
        purchases = SharedPreferencesPurchaseRepository(this)
        val contactRepository = LocalContactRepository(this)
        val contactRelinkRepository = LocalContactRelinkRepository(this)
        val shareRepository = LocalShareRepository(this)
        val shareMetadataRepository = LocalShareMetadataRepository(this)
        val secretRepository = LocalSecretRepository(this)
        val keyConflictRepository = LocalKeyConflictRepository(this)
        val retainedDepositRepository = LocalRetainedDepositRepository(this)
        contactManagement = ContactService(contactRepository, purchases, identityStore, contactRelinkRepository)
        shareManagement = ShareService(
            relayResolver = DeposplitRelayResolver(auth = identityService, relaySettings = relaySettings),
            encryption = identityService,
            shareRepository = shareRepository,
            shareMetadataRepository = shareMetadataRepository,
            secretRepository = secretRepository,
            contactRepository = contactRepository,
            contactManagement = contactManagement,
            keyConflictRepository = keyConflictRepository,
            retainedDepositRepository = retainedDepositRepository,
            identity = identityService,
            purchases = purchases,
        )
        catalogManagement = CatalogService(
            contactRepository = contactRepository,
            secretRepository = secretRepository,
            shareMetadataRepository = shareMetadataRepository,
        )
    }
}
