package com.u1.slicer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.u1.slicer.R
import com.u1.slicer.presets.FullPresetStore
import com.u1.slicer.presets.FullPresetUiState
import com.u1.slicer.presets.FullPresetViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FullPresetSettingsSheet(
    state: FullPresetUiState,
    viewModel: FullPresetViewModel,
    onImportBundle: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.full_presets_setup),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("full-presets-settings-done"),
                ) {
                    Text(stringResource(R.string.full_presets_done))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingsFields(state, viewModel)
                OutlinedButton(
                    onClick = onImportBundle,
                    enabled = state.operation == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("full-presets-import-bundle"),
                ) {
                    Text(stringResource(R.string.full_presets_import_bundle), textAlign = TextAlign.Center)
                }
                Text(
                    stringResource(if (state.bundle == null) R.string.full_presets_bundle_help else R.string.full_presets_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsFields(state: FullPresetUiState, viewModel: FullPresetViewModel) {
    if (state.bundles.isEmpty()) return
    val bundle = state.bundle
    val selection = state.selection
    val selectable = remember(bundle) {
        bundle?.catalog.orEmpty().filter { it.isSelectable }.groupBy { it.category }
            .mapValues { (_, presets) -> presets.map { it.path to it.name } }
    }
    val modelPath = state.model?.file?.path
    val canChooseMapping = modelPath != null && bundle != null && selection != null
    var mappingOpen by rememberSaveable(modelPath) { mutableStateOf(state.filamentMapping != null) }
    var qualityOpen by rememberSaveable(modelPath) { mutableStateOf(false) }
    if (canChooseMapping) {
        FullPresetPrinterFilamentFields(
            state, viewModel, selectable[FullPresetStore.Category.FILAMENT].orEmpty(), mappingOpen,
            onExpandedChanged = { mappingOpen = it },
        )
        if (!mappingOpen) {
            TextButton(
                onClick = { qualityOpen = !qualityOpen },
                modifier = Modifier.testTag("full-presets-printer-and-quality"),
            ) {
                Text(stringResource(
                    if (qualityOpen) R.string.full_presets_quality_hide else R.string.full_presets_quality_show,
                ))
            }
        }
    }
    if (!canChooseMapping || (!mappingOpen && qualityOpen)) {
        PrinterQualityFields(state, viewModel, selectable)
    }
    if (selection == null || state.filamentMapping != null || (canChooseMapping && mappingOpen)) return
    val enabled = state.operation == null
    selection.filaments.forEachIndexed { index, path ->
        PresetPicker(
            tag = "full-presets-filament-${index + 1}",
            label = stringResource(R.string.full_presets_filament, index + 1),
            selected = path,
            entries = selectable[FullPresetStore.Category.FILAMENT].orEmpty(),
            enabled = enabled,
            onSelect = { viewModel.selectFilament(index, it) },
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = viewModel::addFilament,
            enabled = enabled && selection.filaments.size < FullPresetViewModel.MAX_FILAMENTS,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("full-presets-add-filament"),
        ) {
            Text(stringResource(R.string.full_presets_add_filament), textAlign = TextAlign.Center)
        }
        OutlinedButton(
            onClick = viewModel::removeLastFilament,
            enabled = enabled && selection.filaments.size > 1,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("full-presets-remove-filament"),
        ) {
            Text(stringResource(R.string.full_presets_remove_filament), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun PrinterQualityFields(
    state: FullPresetUiState,
    viewModel: FullPresetViewModel,
    selectable: Map<FullPresetStore.Category, List<Pair<String, String>>>,
) {
    val enabled = state.operation == null
    val nameCounts = remember(state.bundles) { state.bundles.groupingBy { it.displayName }.eachCount() }
    PresetPicker(
        tag = "full-presets-bundle",
        label = stringResource(R.string.full_presets_bundle),
        selected = state.bundleId,
        entries = state.bundles.map {
            val name = if (nameCounts.getValue(it.displayName) > 1) {
                stringResource(R.string.full_presets_duplicate_bundle_name, it.displayName, it.id.take(8))
            } else it.displayName
            it.id to name
        },
        enabled = enabled,
        onSelect = viewModel::selectBundle,
    )
    val selection = state.selection ?: return
    PresetPicker(
        tag = "full-presets-printer",
        label = stringResource(R.string.full_presets_printer),
        selected = selection.printer,
        entries = selectable[FullPresetStore.Category.PRINTER].orEmpty(),
        enabled = enabled,
        onSelect = viewModel::selectPrinter,
    )
    PresetPicker(
        tag = "full-presets-process",
        label = stringResource(R.string.full_presets_process),
        selected = selection.process,
        entries = selectable[FullPresetStore.Category.PROCESS].orEmpty(),
        enabled = enabled,
        onSelect = viewModel::selectProcess,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PresetPicker(
    tag: String,
    label: String,
    selected: String?,
    entries: List<Pair<String, String>>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = enabled && expanded,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = entries.firstOrNull { it.first == selected }?.second.orEmpty(),
            onValueChange = {},
            label = { Text(label) },
            readOnly = true,
            enabled = enabled,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(enabled && expanded) },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.menuAnchor().fillMaxWidth().testTag(tag),
        )
        ExposedDropdownMenu(expanded = enabled && expanded, onDismissRequest = { expanded = false }) {
            entries.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    modifier = Modifier.testTag("$tag-option-$id"),
                    onClick = {
                        expanded = false
                        onSelect(id)
                    },
                )
            }
        }
    }
}
