package com.deposplit.value_objects

import java.util.UUID

// The outcome of ShareManagement.reconstruct's over-determination cross-check. NoMargin
// means exactly k shares were available (no surplus to check against — the "reconstructed without
// integrity margin" case). Confirmed means more than k were collected and all of them agreed.
// ExcludedSuspects means more than k were collected, at least one disagreed, and the disagreeing
// share(s) were identified and excluded — the reconstructed secret still comes from a group large
// enough to make that exclusion provably correct (see combineWithIntegrity), not a guess.
sealed class ReconstructionIntegrity {
    object NoMargin : ReconstructionIntegrity()
    object Confirmed : ReconstructionIntegrity()
    data class ExcludedSuspects(val excludedContactIds: Set<UUID>) : ReconstructionIntegrity()
}

// mimeType is the owner's own record of what she split, carried alongside the bytes so a caller
// deciding how to render them never has to go back to the Secret aggregate and risk pairing bytes
// with the wrong type.
data class ReconstructionResult(
    val secret: ByteArray,
    val integrity: ReconstructionIntegrity,
    val mimeType: MimeType,
) {
    // mimeType belongs in both: two results over the same bytes but a different declared type are
    // not the same result, and leaving it out silently made them compare equal.
    override fun equals(other: Any?) = other is ReconstructionResult &&
        secret.contentEquals(other.secret) && integrity == other.integrity && mimeType == other.mimeType
    override fun hashCode() = 31 * (31 * secret.contentHashCode() + integrity.hashCode()) + mimeType.hashCode()
}
