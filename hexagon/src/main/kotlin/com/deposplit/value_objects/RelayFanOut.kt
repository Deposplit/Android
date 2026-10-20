package com.deposplit.value_objects

// The rows every relay that answered returned, and the base URLs of those that did not. A list
// read from the relays alone has nothing local to fall back on, so the caller needs both halves:
// a partial list is still worth showing, and each missing relay is worth naming. anyAnswered
// separates that from a list that is empty only because nobody could be asked.
//
// A relay that answered with an error is named in refusingRelays with its status instead of in
// unreachableRelays, but it returned no rows either, so it does not count towards anyAnswered.
data class RelayFanOut<T>(
    val items: List<T>,
    val unreachableRelays: Set<String>,
    val refusingRelays: Map<String, Int>,
    val anyAnswered: Boolean,
) {
    fun <R> mapItems(transform: (List<T>) -> List<R>): RelayFanOut<R> =
        RelayFanOut(transform(items), unreachableRelays, refusingRelays, anyAnswered)
}
