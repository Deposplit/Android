package com.deposplit.value_objects

/**
 * A relay that answered, but with an error status. A [com.deposplit.driven_ports.ShareRelay]
 * adapter throws this for such an answer and for nothing else, which is what lets a sync pass tell
 * a refusal from silence: anything else an adapter throws means the relay did not answer at all.
 */
class RelayRefusedException(val statusCode: Int, body: String) : Exception("HTTP $statusCode: $body")
