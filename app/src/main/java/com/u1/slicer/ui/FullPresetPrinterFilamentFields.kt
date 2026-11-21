package com.u1.slicer.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.u1.slicer.R
import com.u1.slicer.network.U1FilamentSlot
import com.u1.slicer.presets.FullPresetModelFilaments
import com.u1.slicer.presets.FullPresetPrinterFilaments
import com.u1.slicer.presets.FullPresetUiState
import com.u1.slicer.presets.FullPresetViewModel
import com.u1.slicer.presets.fullPresetPhysicalSlots
import java.io.File
import kotlinx.coroutines.delay

@Composable
internal fun FullPresetPrinterFilamentFields(
    state: FullPresetUiState,
    viewModel: FullPresetViewModel,
    filamentPresets: List<Pair<String, String>>,
    expanded: Boolean,
    onExpandedChanged: (Boolean) -> Unit,
) {
    val model = state.model ?: return
    val modelFilaments = model.filaments
    val applied = state.filamentMapping
    val enabled = state.operation == null
    OutlinedButton(
        enabled = enabled,
        onClick = {
            val opening = !expanded
            onExpandedChanged(opening)
            if (opening && modelFilaments?.canMap == true) viewModel.refreshPrinterFilaments()
        },
        modifier = Modifier.testTag("full-presets-printer-filaments"),
    ) {
        Text(stringResource(if (expanded) R.string.full_presets_mapping_hide else R.string.full_presets_mapping_show))
    }
    if (applied != null) {
        Text(stringResource(R.string.full_presets_mapping_applied), color = MaterialTheme.colorScheme.primary)
        TextButton(
            onClick = {
                viewModel.useManualFilaments()
                onExpandedChanged(false)
            },
            enabled = enabled,
        ) {
            Text(stringResource(R.string.full_presets_mapping_manual))
        }
    }
    if (expanded) {
        if (modelFilaments?.canMap == true) {
            PrinterFilamentMappingForm(state, viewModel, filamentPresets, modelFilaments, model.file)
        } else {
            Text(stringResource(R.string.full_presets_mapping_model_unavailable))
        }
    }
}

@Composable
private fun PrinterFilamentMappingForm(
    state: FullPresetUiState,
    viewModel: FullPresetViewModel,
    filamentPresets: List<Pair<String, String>>,
    modelFilaments: FullPresetModelFilaments,
    modelFile: File,
) {
    val applied = state.filamentMapping
    val enabled = state.operation == null
    var targets by remember(modelFile, state.bundleId, state.selection?.printer, applied) {
        mutableStateOf(applied?.targets.orEmpty())
    }
    val currentPresets = state.selection?.filaments.orEmpty()
    val names = remember(filamentPresets) { filamentPresets.toMap() }
    var presets by remember(modelFile, state.bundleId, state.selection?.printer, applied, currentPresets, names) {
        val selected = applied?.physicalPresets ?: currentPresets
        mutableStateOf(fullPresetPhysicalSlots.mapNotNull { slot ->
            (selected.getOrNull(slot - 1) ?: selected.firstOrNull())
                ?.takeIf { it in names }
                ?.let { slot to it }
        }.toMap())
    }
    var presetsExpanded by rememberSaveable(modelFile.path, state.bundleId) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.full_presets_mapping_explanation), style = MaterialTheme.typography.bodyMedium)
        val report = state.printerFilaments
        val ready = report.displayedReport
        var now by remember(ready) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
        LaunchedEffect(ready) {
            while (ready?.snapshot?.isFresh(now) == true) {
                delay(500)
                now = SystemClock.elapsedRealtime()
            }
        }
        val refreshing = state.refreshingPrinterFilaments
        val fresh = !refreshing && report is FullPresetPrinterFilaments.Ready && report.snapshot.isFresh(now)
        val status = if (refreshing) R.string.full_presets_mapping_loading else when (report) {
            FullPresetPrinterFilaments.Unchecked -> R.string.full_presets_mapping_check
            FullPresetPrinterFilaments.NoConnection -> R.string.full_presets_mapping_no_connection
            is FullPresetPrinterFilaments.Unavailable -> if (ready == null) R.string.full_presets_mapping_unavailable
                else R.string.full_presets_mapping_last_report
            is FullPresetPrinterFilaments.Ready -> if (fresh) R.string.full_presets_mapping_fresh else R.string.full_presets_mapping_stale
        }
        Text(stringResource(status))
        if (ready != null) {
            val slots = ready.snapshot.slots.associateBy(U1FilamentSlot::physicalSlot)
            val loaded = fullPresetPhysicalSlots.mapNotNull(slots::get).filter { it.present == true }
            if (loaded.isEmpty()) Text(stringResource(R.string.full_presets_mapping_no_loaded_slots))
            for (slot in modelFilaments.usedSlots.sorted()) {
                ModelColorMappingCard(
                    sourceSlot = slot,
                    sourceColor = modelFilaments.color(slot),
                    selectedSlot = targets[slot],
                    reported = slots,
                    loaded = loaded,
                    selectedPreset = targets[slot]?.let(presets::get)?.let(names::get),
                    enabled = enabled,
                    onSelect = { target -> targets = targets + (slot to target) },
                )
            }

            val needsPresets = presets.size < fullPresetPhysicalSlots.count()
            Text(
                stringResource(
                    if (needsPresets) R.string.full_presets_mapping_presets_needed
                    else R.string.full_presets_mapping_presets_current,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(
                onClick = { presetsExpanded = !presetsExpanded },
                modifier = Modifier.testTag("full-presets-physical-presets"),
            ) {
                Text(stringResource(
                    if (presetsExpanded) R.string.full_presets_mapping_hide_presets
                    else R.string.full_presets_mapping_choose_presets,
                ))
            }
            if (presetsExpanded) {
                for (slot in fullPresetPhysicalSlots) {
                    PrinterFilamentReport(slot, slots[slot])
                    PresetPicker(
                        tag = "full-presets-map-preset-$slot",
                        label = stringResource(R.string.full_presets_mapping_physical_preset, slot),
                        selected = presets[slot],
                        entries = filamentPresets,
                        enabled = enabled,
                        onSelect = { preset -> presets = presets + (slot to preset) },
                    )
                }
            }
            if (!fresh) Text(stringResource(status))
        }
        TextButton(
            onClick = viewModel::refreshPrinterFilaments,
            enabled = enabled && !refreshing,
            modifier = Modifier.testTag("full-presets-refresh-printer-filaments"),
        ) { Text(stringResource(R.string.full_presets_mapping_refresh)) }
        if (ready != null) {
            Button(
                onClick = { viewModel.applyPrinterFilamentMapping(targets, fullPresetPhysicalSlots.map(presets::getValue)) },
                enabled = enabled && fresh && modelFilaments.positionalMapping(targets) != null &&
                    ready.hasLoadedTargets(targets.values) && presets.size == fullPresetPhysicalSlots.count(),
                modifier = Modifier.testTag("full-presets-apply-printer-filaments"),
            ) { Text(stringResource(R.string.full_presets_mapping_apply)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelColorMappingCard(
    sourceSlot: Int,
    sourceColor: String?,
    selectedSlot: Int?,
    reported: Map<Int, U1FilamentSlot>,
    loaded: List<U1FilamentSlot>,
    selectedPreset: String?,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("full-presets-map-source-$sourceSlot"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilamentSwatch(sourceColor)
                Text(
                    stringResource(R.string.full_presets_mapping_source_color, sourceSlot),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            if (loaded.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (slot in loaded) {
                        val physical = slot.physicalSlot
                        FilterChip(
                            selected = selectedSlot == physical,
                            onClick = { onSelect(physical) },
                            enabled = enabled,
                            leadingIcon = { FilamentSwatch(slot.color) },
                            label = { Text(stringResource(R.string.full_presets_mapping_slot_short, physical)) },
                            modifier = Modifier.testTag("full-presets-map-source-$sourceSlot-option-$physical"),
                        )
                    }
                }
            }
            if (selectedSlot != null) {
                PrinterFilamentReport(selectedSlot, reported[selectedSlot])
                if (selectedPreset != null) {
                    Text(
                        stringResource(R.string.full_presets_mapping_selected_preset, selectedPreset),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                Text(stringResource(R.string.full_presets_mapping_destination), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun PrinterFilamentReport(physicalSlot: Int, filament: U1FilamentSlot?) {
    val unknown = stringResource(R.string.full_presets_mapping_unknown)
    val presence = stringResource(when (filament?.present) {
        true -> R.string.full_presets_mapping_loaded
        false -> R.string.full_presets_mapping_empty
        null -> R.string.full_presets_mapping_presence_unknown
    })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilamentSwatch(filament?.color)
        Text(stringResource(
            R.string.full_presets_mapping_slot_report,
            physicalSlot,
            presence,
            filament?.vendor ?: unknown,
            filament?.material ?: unknown,
            filament?.subtype ?: unknown,
        ))
    }
}

@Composable
internal fun FilamentSwatch(hex: String?) {
    val color = hex?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
        ?: MaterialTheme.colorScheme.surfaceVariant
    val description = stringResource(R.string.full_presets_mapping_color_description, hex ?: stringResource(R.string.full_presets_mapping_unknown))
    Box(
        Modifier.size(18.dp).background(color, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .semantics { contentDescription = description },
    )
}
