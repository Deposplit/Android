package com.deposplit.driven_ports

/**
 * The device's runtime-configurable default relay — used by [ShareRelayResolver] for any
 * [com.deposplit.value_objects.Contact] without an explicit `relayBaseUrl` override, and embedded
 * in this device's own outgoing QR codes so contacts know where to deposit shares for it.
 */
interface RelaySettings {
    fun getDefaultRelayBaseUrl(): String

    /** Passing null resets to the built-in fallback. */
    fun setDefaultRelayBaseUrl(url: String?)
}
