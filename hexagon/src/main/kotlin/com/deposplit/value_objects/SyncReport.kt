package com.deposplit.value_objects

// What a sync pass could not reach. Every relay is soft-failed on its own, so that one dark relay
// cannot blank out the others — which also means a pass never throws for a relay that did not
// answer. This is where that silence is reported instead, by each relay's base URL, so the UI can
// say whose data is only the last known state.
//
// A relay that answered with an error is reported apart from one that did not answer, with the
// status it gave: the two send whoever reads the warning to different places. A relay appears in
// at most one of the two.
data class SyncReport(val unreachableRelays: Set<String>, val refusingRelays: Map<String, Int>) {
    val everyRelayAnswered: Boolean get() = unreachableRelays.isEmpty() && refusingRelays.isEmpty()
}
