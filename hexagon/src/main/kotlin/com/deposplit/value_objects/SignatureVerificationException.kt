package com.deposplit.value_objects

/**
 * Thrown by explicit user-initiated flows ([com.deposplit.driving_adapters.ShareService.respond],
 * [com.deposplit.driving_adapters.ShareService.reconstruct]'s threshold check) when a
 * senderSignature/recipientSignature fails to verify against the sender/recipient's known public
 * key. Background/fan-out flows (`syncInbox`, `listPendingRequests`) silently drop unverified rows
 * instead of throwing, so one bad row from a relay cannot blank out the rest of the poll.
 */
class SignatureVerificationException(message: String) : Exception(message)
