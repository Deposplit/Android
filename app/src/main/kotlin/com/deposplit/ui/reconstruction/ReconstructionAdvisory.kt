package com.deposplit.ui.reconstruction

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.deposplit.R
import com.deposplit.value_objects.ReconstructionIntegrity
import java.util.UUID

/**
 * A one-line advisory summarizing the reconstruction integrity cross-check, shown wherever a
 * reconstructed secret is displayed (ShareDetailScreen, RepairScreen).
 */
@Composable
fun ReconstructionAdvisory(
    integrity: ReconstructionIntegrity,
    contactName: (UUID) -> String,
    modifier: Modifier = Modifier,
) {
    val (icon, color, text) = when (integrity) {
        is ReconstructionIntegrity.NoMargin -> Triple(
            Icons.Filled.Info,
            MaterialTheme.colorScheme.onSurfaceVariant,
            stringResource(R.string.reconstruction_advisory_no_margin),
        )
        is ReconstructionIntegrity.Confirmed -> Triple(
            Icons.Filled.CheckCircle,
            MaterialTheme.colorScheme.primary,
            stringResource(R.string.reconstruction_advisory_confirmed),
        )
        is ReconstructionIntegrity.ExcludedSuspects -> {
            val names = integrity.excludedContactIds.map(contactName).sorted().joinToString(", ")
            Triple(
                Icons.Filled.Warning,
                MaterialTheme.colorScheme.error,
                stringResource(R.string.reconstruction_advisory_excluded, integrity.excludedContactIds.size, names),
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}
