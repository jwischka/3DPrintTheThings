package com.u1.slicer.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.u1.slicer.R
import com.u1.slicer.presets.FullPresetOperation
import com.u1.slicer.presets.FullPresetStore
import com.u1.slicer.presets.FullPresetViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPresetScreen(onBack: () -> Unit) {
    val model: FullPresetViewModel = viewModel()
    val state by model.state.collectAsState()
    val enabled = state.operation == null
    val bundle = state.bundle
    val selection = state.selection
    val importedModel = state.model
    val output = state.output

    val importBundle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(model::importBundle)
    }
    val importModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(model::importModel)
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {
        it?.let(model::exportGcode)
    }

    BackHandler { if (enabled) onBack() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.full_presets_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = enabled) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.full_presets_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.full_presets_description), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(
                onClick = { importBundle.launch(arrayOf("*/*")) },
                enabled = enabled,
                modifier = Modifier.testTag("full-presets-import-bundle"),
            ) {
                Text(stringResource(R.string.full_presets_import_bundle))
            }
            if (state.bundles.isNotEmpty()) {
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
                    onSelect = model::selectBundle,
                )
            }
            if (bundle != null && selection != null) {
                val selectable = remember(bundle) {
                    bundle.catalog.filter { it.isSelectable }.groupBy { it.category }
                        .mapValues { (_, presets) -> presets.map { it.path to it.name } }
                }
                PresetPicker(
                    tag = "full-presets-printer",
                    label = stringResource(R.string.full_presets_printer),
                    selected = selection.printer,
                    entries = selectable[FullPresetStore.Category.PRINTER].orEmpty(),
                    enabled = enabled,
                    onSelect = model::selectPrinter,
                )
                PresetPicker(
                    tag = "full-presets-process",
                    label = stringResource(R.string.full_presets_process),
                    selected = selection.process,
                    entries = selectable[FullPresetStore.Category.PROCESS].orEmpty(),
                    enabled = enabled,
                    onSelect = model::selectProcess,
                )
                selection.filaments.forEachIndexed { index, path ->
                    PresetPicker(
                        tag = "full-presets-filament-${index + 1}",
                        label = stringResource(R.string.full_presets_filament, index + 1),
                        selected = path,
                        entries = selectable[FullPresetStore.Category.FILAMENT].orEmpty(),
                        enabled = enabled,
                        onSelect = { model.selectFilament(index, it) },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = model::addFilament,
                        enabled = enabled && selection.filaments.size < FullPresetViewModel.MAX_FILAMENTS,
                        modifier = Modifier.weight(1f).testTag("full-presets-add-filament"),
                    ) {
                        Text(stringResource(R.string.full_presets_add_filament))
                    }
                    OutlinedButton(
                        onClick = model::removeLastFilament,
                        enabled = enabled && selection.filaments.size > 1,
                        modifier = Modifier.weight(1f).testTag("full-presets-remove-filament"),
                    ) {
                        Text(stringResource(R.string.full_presets_remove_filament))
                    }
                }
            }
            OutlinedButton(
                onClick = { importModel.launch(arrayOf("*/*")) },
                enabled = enabled,
                modifier = Modifier.testTag("full-presets-import-model"),
            ) {
                Text(stringResource(if (importedModel == null) R.string.full_presets_import_model else R.string.full_presets_change_model))
            }
            importedModel?.let { Text(it.name, style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = model::slice,
                    enabled = enabled && bundle != null && selection != null && importedModel != null,
                    modifier = Modifier.testTag("full-presets-slice"),
                ) {
                    Text(stringResource(R.string.full_presets_slice))
                }
                if (state.operation == FullPresetOperation.SLICING || state.operation == FullPresetOperation.CANCELLING) {
                    OutlinedButton(
                        onClick = model::cancelSlice,
                        enabled = state.operation == FullPresetOperation.SLICING,
                        modifier = Modifier.testTag("full-presets-cancel"),
                    ) {
                        Text(stringResource(R.string.full_presets_cancel))
                    }
                }
            }
            state.operation?.let { operation ->
                val progress = state.progress
                if (progress == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
                Text(stringResource(operation.label), style = MaterialTheme.typography.bodyMedium)
            }
            state.error?.let { notice ->
                val message = notice.detail?.let {
                    stringResource(R.string.full_presets_error_detail, stringResource(notice.message), it)
                } ?: stringResource(notice.message)
                Text(message, color = MaterialTheme.colorScheme.error)
            }
            state.status?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
            if (output != null) {
                val layers = output.result.totalLayers
                Text(pluralStringResource(R.plurals.full_presets_slice_complete, layers, layers))
                Button(
                    onClick = { export.launch(output.filename) },
                    enabled = enabled,
                    modifier = Modifier.testTag("full-presets-export"),
                ) {
                    Text(stringResource(R.string.full_presets_export))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetPicker(
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
