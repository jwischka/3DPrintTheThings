package com.u1.slicer.ui

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.u1.slicer.R
import com.u1.slicer.presets.FullPresetPrintPhase
import com.u1.slicer.presets.FullPresetPrintReview
import com.u1.slicer.presets.FullPresetPrinterFilaments
import java.util.Date

@Composable
internal fun FullPresetPrintReviewDialog(
    review: FullPresetPrintReview,
    printInProgress: Boolean,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onConfirm: () -> Unit,
    onPrintSetup: () -> Unit,
) {
    val ready = review.phase == FullPresetPrintPhase.READY
    val sending = review.phase == FullPresetPrintPhase.UPLOADING || review.phase == FullPresetPrintPhase.STARTING
    val busy = printInProgress || sending || review.phase == FullPresetPrintPhase.CHECKING
    val report = review.report
    // A failed readiness check must not erase the spool choices used for slicing.
    // This fallback is display-only; only a successful preflight enables Print.
    val displayedReport = report ?: review.output.mapping?.confirmed
    val canPrint = !busy && ready && report != null && review.job != null && review.output.mapping != null
    val needsSetup = review.output.mapping == null || review.message in setOf(
        R.string.full_presets_print_material_mismatch,
        R.string.full_presets_print_filaments_changed,
        R.string.full_presets_print_connection_changed,
        R.string.full_presets_print_output_changed,
    )
    val message = review.message ?: when (review.phase) {
        FullPresetPrintPhase.CHECKING -> R.string.full_presets_print_review_checking
        FullPresetPrintPhase.READY -> R.string.full_presets_print_review_ready
        FullPresetPrintPhase.UPLOADING -> R.string.full_presets_print_sending
        FullPresetPrintPhase.STARTING -> R.string.full_presets_print_review_starting
        FullPresetPrintPhase.STARTED -> R.string.full_presets_print_review_started
        FullPresetPrintPhase.UNCERTAIN -> R.string.full_presets_print_review_uncertain
        FullPresetPrintPhase.FAILED -> R.string.full_presets_print_failed
    }

    AlertDialog(
        modifier = Modifier.testTag("full-presets-print-review"),
        onDismissRequest = { if (!printInProgress) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !printInProgress, dismissOnClickOutside = !printInProgress),
        title = { Text(stringResource(R.string.full_presets_print_review_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(review.output.filename, style = MaterialTheme.typography.titleSmall)
                val endpoint = report?.connection?.endpoint ?: review.output.mapping?.confirmed?.connection?.endpoint
                if (endpoint != null) {
                    Text(stringResource(R.string.full_presets_print_review_printer, endpoint))
                }
                Text(
                    stringResource(message),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (busy) {
                    val percent = review.progressPercent
                    if (percent == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = { percent.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                }
                if (displayedReport != null) {
                    if (report == null) {
                        Text(
                            stringResource(R.string.full_presets_print_review_retained_filaments),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    val time = DateFormat.getTimeFormat(LocalContext.current).format(Date(displayedReport.snapshot.receivedAtMillis))
                    Text(
                        stringResource(R.string.full_presets_print_review_reported_at, time),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                PrintToolUsage(review, displayedReport)
                if (!busy && review.phase == FullPresetPrintPhase.FAILED && needsSetup) {
                    TextButton(
                        onClick = onPrintSetup,
                        modifier = Modifier.testTag("full-presets-print-review-setup"),
                    ) {
                        Text(stringResource(R.string.full_presets_print_review_setup))
                    }
                } else if (!busy && review.phase == FullPresetPrintPhase.FAILED) {
                    TextButton(
                        onClick = onRefresh,
                        modifier = Modifier.testTag("full-presets-print-review-refresh"),
                    ) {
                        Text(stringResource(R.string.full_presets_print_review_refresh))
                    }
                }
            }
        },
        confirmButton = {
            if (ready) {
                Button(
                    onClick = onConfirm,
                    enabled = canPrint,
                    modifier = Modifier.testTag("full-presets-confirm-print"),
                ) {
                    Text(stringResource(R.string.full_presets_print_review_confirm))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !printInProgress,
                modifier = Modifier.testTag("full-presets-close-print-review"),
            ) {
                Text(stringResource(
                    if (ready || busy) R.string.full_presets_cancel else R.string.full_presets_done,
                ))
            }
        },
    )
}

@Composable
private fun PrintToolUsage(review: FullPresetPrintReview, report: FullPresetPrinterFilaments.Ready?) {
    val usage = review.usage ?: return
    val slots = report?.snapshot?.slots.orEmpty()
    val locale = LocalConfiguration.current.locales[0]
    val measure = remember(locale) { MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.SHORT) }
    Text(stringResource(R.string.full_presets_print_review_tools), style = MaterialTheme.typography.titleSmall)
    for (slot in usage.requiredSlots.sorted()) {
        val report = slots.firstOrNull { it.physicalSlot == slot }
        val grams = usage.gramsBySlot?.getOrNull(slot - 1)
        val preset = review.presetNames.getOrNull(slot - 1)
        Surface(
            modifier = Modifier.fillMaxWidth().testTag("full-presets-print-tool-$slot"),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PrinterFilamentReport(slot, report)
                Text(
                    if (preset == null) stringResource(R.string.full_presets_print_review_unknown_preset)
                    else stringResource(R.string.full_presets_mapping_selected_preset, preset),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    if (grams == null) stringResource(R.string.full_presets_print_review_unknown_usage)
                    else stringResource(R.string.full_presets_print_review_estimated_usage, measure.format(Measure(grams, MeasureUnit.GRAM))),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    Text(
        stringResource(R.string.full_presets_print_review_check_preset),
        style = MaterialTheme.typography.bodySmall,
    )
}
