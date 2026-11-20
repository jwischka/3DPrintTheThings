package com.u1.slicer.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.u1.slicer.R
import com.u1.slicer.presets.FullPresetNotice
import com.u1.slicer.presets.FullPresetOperation
import com.u1.slicer.presets.FullPresetUiState
import java.text.NumberFormat

@Composable
internal fun PrepareActionBar(
    state: FullPresetUiState,
    onImportModel: () -> Unit,
    onImportBundle: () -> Unit,
    onSlice: () -> Unit,
    onCancel: () -> Unit,
    onExport: (String) -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.error?.let { WorkflowError(it) }
            state.status?.let {
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            val operation = state.operation
            val output = state.output
            when {
                operation != null -> OperationProgress(operation, state.progress, onCancel)
                output != null -> PrimaryAction(
                    R.string.full_presets_export, Icons.Outlined.Download, "full-presets-export",
                ) { onExport(output.filename) }
                state.model == null -> PrimaryAction(
                    R.string.full_presets_import_model, Icons.Outlined.UploadFile, "full-presets-import-model", onImportModel,
                )
                state.selection == null || state.bundle == null -> PrimaryAction(
                    R.string.full_presets_import_presets, Icons.Outlined.UploadFile, "full-presets-import-presets", onImportBundle,
                )
                else -> PrimaryAction(
                    R.string.full_presets_slice, Icons.Outlined.Layers, "full-presets-slice", onSlice,
                )
            }
        }
    }
}

@Composable
private fun PrimaryAction(@StringRes label: Int, icon: ImageVector, tag: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(tag),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(label), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
    }
}

@Composable
private fun OperationProgress(operation: FullPresetOperation, progress: Int?, onCancel: () -> Unit) {
    val cancellable = operation == FullPresetOperation.SLICING
    val cancelling = operation == FullPresetOperation.CANCELLING
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(operation.label),
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.titleSmall,
        )
        progress?.let {
            val format = remember { NumberFormat.getPercentInstance() }
            Text(format.format(it / 100.0), style = MaterialTheme.typography.labelLarge)
        }
        if (cancellable || cancelling) {
            TextButton(
                onClick = onCancel,
                enabled = cancellable,
                modifier = Modifier.testTag("full-presets-cancel").heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.full_presets_cancel))
            }
        }
    }
    if (progress == null) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun WorkflowError(notice: FullPresetNotice) {
    val message = stringResource(notice.message)
    var showDetail by remember(notice) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
            Text(
                message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(
                onClick = { showDetail = true },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.full_presets_error_details))
            }
        }
    }
    if (showDetail) {
        AlertDialog(
            onDismissRequest = { showDetail = false },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(message)
                    notice.detail?.let { Text(it) }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDetail = false }) {
                    Text(stringResource(R.string.full_presets_done))
                }
            },
        )
    }
}
