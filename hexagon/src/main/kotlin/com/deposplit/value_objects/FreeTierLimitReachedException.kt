package com.deposplit.value_objects

/**
 * Thrown by [com.deposplit.driving_adapters.ShareService.deposit] when a device without Premium
 * already holds [SecretLimits.FREE_TIER_MAX_ACTIVE_SECRETS] active secrets.
 *
 * Carries the numbers rather than a sentence, so the caller can say what happened in its own
 * language — and so a paywall can show how full the free tier is without counting again.
 */
class FreeTierLimitReachedException(val active: Int, val limit: Int) :
    Exception("$active of $limit free secrets are already active")
