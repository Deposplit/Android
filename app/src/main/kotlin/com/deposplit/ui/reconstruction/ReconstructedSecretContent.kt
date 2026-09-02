package com.deposplit.ui.reconstruction

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.deposplit.R
import com.deposplit.value_objects.MimeType
import java.io.ByteArrayOutputStream

/**
 * Renders whatever `reconstruct` produced, forking on [ReconstructedSecret]. Shown wherever a
 * reconstructed secret is displayed, alongside `ReconstructionAdvisory`.
 *
 * The export writes the reconstructed *plaintext* to a location the user picks, through the same
 * Storage Access Framework contract the catalogue backup uses. That is a real confidentiality
 * surface — unlike the catalogue, which carries no shares and no keys — so it is offered per
 * reconstruction, at the user's request, and never written anywhere on its own initiative.
 */
@Composable
fun ReconstructedSecretContent(
    secret: ReconstructedSecret,
    mimeType: MimeType,
    label: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val exportBytes = when (secret) {
        is ReconstructedSecret.Text -> secret.text.toByteArray(Charsets.UTF_8)
        is ReconstructedSecret.Image -> secret.bitmap.toPngBytes()
        is ReconstructedSecret.Binary -> secret.bytes
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(mimeType.value),
    ) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(exportBytes) } }
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (secret) {
            is ReconstructedSecret.Text ->
                Text(
                    text = secret.text,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )

            is ReconstructedSecret.Image ->
                Image(
                    bitmap = secret.bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.reconstructed_image_description),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).padding(12.dp),
                )

            is ReconstructedSecret.Binary ->
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "${mimeType.value} · ${formatByteCount(secret.bytes.size)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.reconstructed_binary_explanation),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
        }

        TextButton(onClick = { exportLauncher.launch(exportFileName(label, mimeType)) }) {
            Text(stringResource(R.string.reconstructed_export_button))
        }
    }
}

/**
 * Named from the label and the declared type, so the exported file arrives somewhere useful with an
 * extension its destination understands. An unrecognised type simply gets none.
 */
private fun exportFileName(label: String, mimeType: MimeType): String {
    val base = label.trim().ifEmpty { "secret" }.replace('/', '-')
    val extension = android.webkit.MimeTypeMap.getSingleton()
        .getExtensionFromMimeType(mimeType.value.substringBefore(';').trim().lowercase())
    return if (extension != null) "$base.$extension" else base
}

private fun android.graphics.Bitmap.toPngBytes(): ByteArray =
    ByteArrayOutputStream().also { compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
