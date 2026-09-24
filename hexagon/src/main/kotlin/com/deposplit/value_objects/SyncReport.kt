package com.deposplit.value_objects

// What a sync pass could not reach. Every relay is soft-failed on its own, so that one dark relay
// cannot blank out the others — which also means a pass never throws for a relay that did not
// answer. This is where that silence is reported instead, by each relay's base URL, so the UI can
// say whose data is only the last known state.
data class SyncReport(val unreachableRelays: Set<String>)
