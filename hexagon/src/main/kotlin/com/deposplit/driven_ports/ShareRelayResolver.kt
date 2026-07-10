package com.deposplit.driven_ports

/**
 * Resolves which [ShareRelay] to use for a given contact's BYOR override — a factory/cache, not
 * a fan-out mechanism (fan-out across multiple relays is a ShareService-level policy decision,
 * not an infrastructure concern). `null` resolves to the device's configured default relay
 * ([RelaySettings]).
 */
interface ShareRelayResolver {
    fun resolve(relayBaseUrl: String?): ShareRelay
}
