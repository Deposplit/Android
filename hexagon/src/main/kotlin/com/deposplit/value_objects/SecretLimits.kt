package com.deposplit.value_objects

/**
 * The bounds a deposit must clear. One is a cost of the protocol, the other a cost of the price
 * list; they are unrelated, and only the first would exist if Deposplit were free.
 */
object SecretLimits {
    /**
     * The largest secret that may be split.
     *
     * Shamir shares are byte-wise: an S-byte secret becomes `n` shares of S bytes each, every one
     * of them encrypted, base64-encoded into its own request, and held by the relay until its
     * holder picks it up — while the sender retains a copy of all `n` until every pickup is
     * confirmed. The cost of a secret is therefore several times its size, several times over,
     * which is why there is a limit at all and why it is this modest.
     *
     * It applies to every secret uniformly, typed text included, and is enforced in the domain
     * rather than at an input form so that no entry point can slip past it — a re-split during a
     * repair least of all.
     *
     * The relay enforces a bound of its own, deliberately looser: it cannot know what any client's
     * limit is.
     */
    const val MAX_SECRET_BYTES = 256 * 1024

    /**
     * How many secrets may be active at once without the Premium unlock.
     *
     * A business rule, not a protocol constraint — nothing breaks at four, and the relay neither
     * knows nor could enforce this. It counts secrets that are *active*, so discarding one frees a
     * slot the moment the removal requests go out rather than when the last holder confirms. A
     * repair's re-split is exempt: it replaces an active secret rather than adding one.
     */
    const val FREE_TIER_MAX_ACTIVE_SECRETS = 3
}
