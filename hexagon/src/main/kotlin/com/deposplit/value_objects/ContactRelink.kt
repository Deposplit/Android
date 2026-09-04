package com.deposplit.value_objects

import java.time.Instant
import java.util.UUID

/**
 * Evidence that a contact has relinked this device's *current* identity — that they hold the key
 * this device signs with today, not the one it lost.
 *
 * Kept in its own store rather than as a field on [Contact], for two reasons. It describes this
 * device's identity rather than the contact, so it has no place in a catalogue export, which is a
 * backup of contacts. And a new field on `Contact` has to be threaded by hand through Swift's
 * memberwise initialisers and Android's hand-written catalogue codec, which has silently dropped
 * fields before.
 *
 * [observedAt] is either the moment something signed arrived from that contact — the relay only
 * ever returns rows addressed to the caller's current key, so receiving anything is proof — or the
 * moment the user said they had met them. The two are not distinguished, because nothing acts on
 * the difference.
 */
data class ContactRelink(
    val contactId: UUID,
    val observedAt: Instant,
)
