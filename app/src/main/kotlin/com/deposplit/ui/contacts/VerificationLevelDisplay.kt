package com.deposplit.ui.contacts

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.deposplit.R
import com.deposplit.value_objects.VerificationLevel

// Shared label/guidance/color mapping for VerificationLevel, used by ContactsScreen, DepositScreen,
// and AddContactScreen — see deposplit.com/CLAUDE.md "What is next" item 6.

@Composable
fun VerificationLevel.displayName(): String = stringResource(
    when (this) {
        VerificationLevel.VERY_LOW -> R.string.verification_level_very_low
        VerificationLevel.LOW -> R.string.verification_level_low
        VerificationLevel.HIGH -> R.string.verification_level_high
        VerificationLevel.VERY_HIGH -> R.string.verification_level_very_high
    }
)

@Composable
fun VerificationLevel.guidance(): String = stringResource(
    when (this) {
        VerificationLevel.VERY_LOW -> R.string.verification_level_very_low_guidance
        VerificationLevel.LOW -> R.string.verification_level_low_guidance
        VerificationLevel.HIGH -> R.string.verification_level_high_guidance
        VerificationLevel.VERY_HIGH -> R.string.verification_level_very_high_guidance
    }
)

@Composable
fun VerificationLevel.badgeColor(): Color = when (this) {
    VerificationLevel.VERY_LOW -> MaterialTheme.colorScheme.onSurfaceVariant
    VerificationLevel.LOW -> Color(0xFFC79100)
    VerificationLevel.HIGH -> MaterialTheme.colorScheme.primary
    VerificationLevel.VERY_HIGH -> Color(0xFF2E7D32)
}
