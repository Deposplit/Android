package com.deposplit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.net.URI

// A relay as a person would recognise it: its host, and its port when it has one. The scheme and
// any path add length without telling two relays apart. Falls back to the URL as stored, since a
// warning that names a relay oddly is still better than one that names none.
fun relayName(baseUrl: String): String = runCatching {
    val uri = URI(baseUrl)
    val host = uri.host ?: return@runCatching baseUrl
    if (uri.port == -1) host else "$host:${uri.port}"
}.getOrDefault(baseUrl)

// One soft warning line, the kind that sits over real content rather than replacing it.
@Composable
fun SoftWarningRow(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
