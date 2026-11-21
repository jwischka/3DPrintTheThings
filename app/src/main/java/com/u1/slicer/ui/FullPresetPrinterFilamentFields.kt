package com.u1.slicer.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import com.u1.slicer.R
import com.u1.slicer.network.U1FilamentSlot
import com.u1.slicer.presets.FullPresetPrinterFilaments
import com.u1.slicer.presets.FullPresetUiState
import com.u1.slicer.presets.FullPresetViewModel
import com.u1.slicer.presets.fullPresetPhysicalSlots
import kotlinx.coroutines.delay

@Composable
internal fun FullPresetPrinterFilamentFields(
    state: FullPresetUiState,
    viewModel: FullPresetViewModel,
    filamentPresets: List<Pair<String, String>>,
) {
    val model = state.model ?: return
    val modelFilaments = model.filaments
    val applied = state.filamentMapping
    val enabled = state.operation == null
    var expanded by rememberSaveable(model.file.path) { mutableStateOf(applied != null) }
    OutlinedButton(
        enabled = enabled,
        onClick = {
            expanded = !expanded
            if (expanded && modelFilaments?.canMap == true) viewModel.refreshPrinterFilaments()
        },
        modifier = Modifier.testTag("full-presets-printer-filaments"),
    ) {
        Text(stringResource(if (expanded) R.string.full_presets_mapping_hide else R.string.full_presets_mapping_show))
    }
    if (applied != null) {
        Text(stringResource(R.string.full_presets_mapping_applied), color = MaterialTheme.colorScheme.primary)
        TextButton(onClick = viewModel::useManualFilaments, enabled = enabled) {
            Text(stringResource(R.string.full_presets_mapping_manual))
        }
    }
    if (!expanded) return
    if (modelFilaments?.canMap != true) {
        Text(stringResource(R.string.full_presets_mapping_model_unavailable))
        return
    }

    var targets by remember(model.file, state.bundleId, state.selection?.printer, applied) {
        mutableStateOf(applied?.targets.orEmpty())
    }
    var presets by remember(model.file, state.bundleId, state.selection?.printer, applied) {
        mutableStateOf(applied?.physicalPresets.orEmpty().mapIndexed { index, path -> (index + 1) to path }.toMap())
    }

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
        if (ready == null) {
            TextButton(onClick = viewModel::refreshPrinterFilaments, enabled = enabled && !refreshing) {
                Text(stringResource(R.string.full_presets_mapping_refresh))
            }
            return@Column
        }

        val loadedSlots = ready.snapshot.slots.filter { it.present == true }.sortedBy { it.physicalSlot }
        val choices = loadedSlots.map { slot ->
            val unknown = stringResource(R.string.full_presets_mapping_unknown)
            slot.physicalSlot.toString() to stringResource(
                R.string.full_presets_mapping_physical_choice,
                slot.physicalSlot,
                slot.material ?: unknown,
                slot.subtype ?: unknown,
                slot.color ?: unknown,
            )
        }

        for (slot in modelFilaments.usedSlots.sorted()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val color = modelFilaments.color(slot)
                FilamentSwatch(color)
                Text(stringResource(R.string.full_presets_mapping_source_slot, slot, color ?: stringResource(R.string.full_presets_mapping_unknown)))
            }
            PresetPicker(
                tag = "full-presets-map-source-$slot",
                label = stringResource(R.string.full_presets_mapping_destination),
                selected = targets[slot]?.toString(),
                entries = choices,
                enabled = enabled && choices.isNotEmpty(),
                onSelect = { target -> targets = targets + (slot to target.toInt()) },
            )
        }

        HorizontalDivider()
        Text(stringResource(R.string.full_presets_mapping_choose_presets), style = MaterialTheme.typography.titleSmall)
        for (slot in fullPresetPhysicalSlots) {
            val filament = ready.snapshot.slots.firstOrNull { it.physicalSlot == slot }
            PrinterFilamentReport(slot, filament)
            PresetPicker(
                tag = "full-presets-map-preset-$slot",
                label = stringResource(R.string.full_presets_mapping_physical_preset, slot),
                selected = presets[slot],
                entries = filamentPresets,
                enabled = enabled,
                onSelect = { preset -> presets = presets + (slot to preset) },
            )
        }
        if (!fresh) Text(stringResource(status))
        TextButton(onClick = viewModel::refreshPrinterFilaments, enabled = enabled && !refreshing) {
            Text(stringResource(R.string.full_presets_mapping_refresh))
        }
        Button(
            onClick = { viewModel.applyPrinterFilamentMapping(targets, fullPresetPhysicalSlots.map(presets::getValue)) },
            enabled = enabled && fresh && modelFilaments.positionalMapping(targets) != null &&
                ready.hasLoadedTargets(targets.values) && presets.size == fullPresetPhysicalSlots.count(),
            modifier = Modifier.testTag("full-presets-apply-printer-filaments"),
        ) { Text(stringResource(R.string.full_presets_mapping_apply)) }
    }
}

@Composable
private fun PrinterFilamentReport(physicalSlot: Int, filament: U1FilamentSlot?) {
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
            filament?.color ?: unknown,
        ))
    }
}

@Composable
private fun FilamentSwatch(hex: String?) {
    val color = hex?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
        ?: MaterialTheme.colorScheme.surfaceVariant
    Box(Modifier.size(18.dp).background(color, CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
}
