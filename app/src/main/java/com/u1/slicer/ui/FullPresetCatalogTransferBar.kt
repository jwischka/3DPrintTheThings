package com.u1.slicer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.u1.slicer.R
import com.u1.slicer.presets.FullPresetCatalogTransfer

@Composable
internal fun FullPresetCatalogTransferBar(
    transfer: FullPresetCatalogTransfer,
    workspaceBusy: Boolean,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onOpen: (FullPresetCatalogTransfer.Ready) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("full-presets-catalog-transfer"),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (transfer) {
                FullPresetCatalogTransfer.Idle -> Unit
                is FullPresetCatalogTransfer.InProgress -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (transfer.cancelling) stringResource(R.string.full_presets_catalog_cancelling)
                            else stringResource(transfer.label, transfer.fileName),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(
                            onClick = onCancel,
                            enabled = !transfer.cancelling,
                            modifier = Modifier.testTag("full-presets-catalog-cancel"),
                        ) {
                            Text(stringResource(R.string.library_cancel))
                        }
                    }
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is FullPresetCatalogTransfer.Ready -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.full_presets_catalog_saved, transfer.entry.fileName),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(
                        onClick = { onOpen(transfer) },
                        enabled = !workspaceBusy,
                        modifier = Modifier.testTag("full-presets-catalog-open"),
                    ) { Text(stringResource(R.string.library_open)) }
                    CatalogDismissButton(onDismiss)
                }
                is FullPresetCatalogTransfer.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(transfer.message),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    CatalogDismissButton(onDismiss)
                }
            }
            if (workspaceBusy && (transfer is FullPresetCatalogTransfer.Ready ||
                    transfer is FullPresetCatalogTransfer.Idle)
            ) {
                Text(
                    text = stringResource(R.string.full_presets_catalog_workspace_busy),
                    modifier = Modifier.padding(vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun CatalogDismissButton(onDismiss: () -> Unit) {
    IconButton(onClick = onDismiss) {
        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.full_presets_catalog_dismiss))
    }
}
