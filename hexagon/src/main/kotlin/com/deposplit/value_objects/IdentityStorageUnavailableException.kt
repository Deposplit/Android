package com.deposplit.value_objects

/**
 * Thrown by an [com.deposplit.driven_ports.IdentityStore] when private key material cannot be read
 * *right now* — a locked device, a keystore that is not yet available — as opposed to not being
 * there at all.
 *
 * Only an adapter can tell those apart, because only an adapter sees the platform's own error, so
 * the distinction has to be made there and carried in the type. See
 * [IdentityIntegrity.UNREADABLE] for what depends on it.
 */
class IdentityStorageUnavailableException(message: String) : Exception(message)
