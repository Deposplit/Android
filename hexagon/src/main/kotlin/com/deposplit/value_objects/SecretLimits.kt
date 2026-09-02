package com.deposplit.value_objects

/**
 * The largest secret that may be split.
 *
 * Shamir shares are byte-wise: an S-byte secret becomes `n` shares of S bytes each, every one of
 * them encrypted, base64-encoded into its own request, and held by the relay until its holder picks
 * it up — while the sender retains a copy of all `n` until every pickup is confirmed. The cost of a
 * secret is therefore several times its size, several times over, which is why there is a limit at
 * all and why it is this modest.
 *
 * It applies to every secret uniformly, typed text included, and is enforced in the domain rather
 * than at an input form so that no entry point can slip past it — a re-split during a repair least
 * of all.
 *
 * The relay enforces a bound of its own, deliberately looser: it cannot know what any client's
 * limit is.
 */
object SecretLimits {
    const val MAX_SECRET_BYTES = 256 * 1024
}
