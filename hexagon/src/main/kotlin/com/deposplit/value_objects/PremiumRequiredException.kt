package com.deposplit.value_objects

/**
 * Thrown when a device without Premium reaches for a paid capability that has no free equivalent —
 * today, naming a contact's relay by hand.
 *
 * Carries nothing: unlike [FreeTierLimitReachedException] there is no quantity to report, only a
 * boundary. The caller decides which paywall copy fits the surface the user was on.
 */
class PremiumRequiredException : Exception("this requires the Deposplit Premium unlock")
