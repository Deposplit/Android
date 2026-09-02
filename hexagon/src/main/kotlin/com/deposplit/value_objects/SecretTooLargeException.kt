package com.deposplit.value_objects

/**
 * Thrown by [com.deposplit.driving_adapters.ShareService.deposit] when a secret exceeds
 * [SecretLimits.MAX_SECRET_BYTES].
 *
 * Carries the numbers rather than a sentence, so the caller can say what happened in its own
 * language.
 */
class SecretTooLargeException(val bytes: Int, val limit: Int) :
    Exception("secret is $bytes bytes; the limit is $limit")
