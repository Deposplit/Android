package com.deposplit.value_objects

// Outcome of ShareManagement.regenerateIdentity() — how many of this device's contacts were
// successfully notified of the new key via a signed rotation push before the new identity was
// activated locally. A contact not reached here never learns of the new key automatically; there
// is no retry mechanism, matching pushRotation's own one-shot semantics.
data class RegenerateIdentityResult(
    val notifiedContacts: Int,
    val totalContacts: Int,
)
