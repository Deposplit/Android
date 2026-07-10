package com.deposplit.api

import com.deposplit.driven_ports.RelaySettings
import com.deposplit.driven_ports.ShareRelay
import com.deposplit.driven_ports.ShareRelayResolver
import com.deposplit.driving_ports.Identity
import java.util.concurrent.ConcurrentHashMap

/** Memoizes one [DeposplitApiAdapter] per resolved base URL so HTTP clients aren't rebuilt on
 * every call. `null` resolves to [RelaySettings]'s runtime-configurable default.
 */
class DeposplitRelayResolver(
    private val auth: Identity,
    private val relaySettings: RelaySettings,
) : ShareRelayResolver {

    private val cache = ConcurrentHashMap<String, ShareRelay>()

    override fun resolve(relayBaseUrl: String?): ShareRelay {
        val url = relayBaseUrl ?: relaySettings.getDefaultRelayBaseUrl()
        return cache.getOrPut(url) { DeposplitApiAdapter(auth = auth, baseUrl = url) }
    }
}
