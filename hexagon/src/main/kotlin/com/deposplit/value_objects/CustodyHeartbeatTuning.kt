package com.deposplit.value_objects

import java.time.Duration

/**
 * Item 12's cadence/staleness numbers — UI tuning, not load-bearing spec (see
 * deposplit.com/CLAUDE.md "What is next" item 12: "the load-bearing part is not the interval
 * number ... but two guarantees"). Shared between [com.deposplit.driving_adapters.ShareService]
 * (emission cadence, on the holder side) and the app layer's health/freshness display (on the
 * owner side) so both halves agree on the same numbers.
 */
object CustodyHeartbeatTuning {
    /**
     * How often a holder re-emits a heartbeat to a given sender, opportunistically piggybacked
     * on the existing inbox poll — not a background timer.
     */
    val emissionInterval: Duration = Duration.ofDays(3)

    /**
     * A holder confirmed within this window still counts toward `n_live`. Set well above
     * [emissionInterval] so a single missed beat is never mistaken for loss (guarantee (b)).
     */
    val lossThreshold: Duration = emissionInterval.multipliedBy(3)

    /**
     * Below [lossThreshold] but past this point, the UI nudges "getting stale" — the early
     * warning called for in item 12, surfaced before a holder actually drops out of `n_live`.
     */
    val staleWarningThreshold: Duration = emissionInterval.multipliedBy(2)
}
