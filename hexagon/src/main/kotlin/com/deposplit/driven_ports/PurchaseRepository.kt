package com.deposplit.driven_ports

/**
 * Whether this device has the one-time Deposplit Premium unlock.
 *
 * Deliberately synchronous and deliberately local. The relay never learns payment status, so there
 * is nothing to ask it; and a device that cannot reach the store must still be able to split a
 * secret, so the answer is a cached one the adapter refreshes rather than a live query. The domain
 * only ever reads it.
 *
 * Enforcement is client-side and therefore honour-system by design — see SECURITY.md. What this
 * port buys is not unforgeability but a single place where the free/paid boundary is stated.
 */
interface PurchaseRepository {
    fun isPremium(): Boolean
}
