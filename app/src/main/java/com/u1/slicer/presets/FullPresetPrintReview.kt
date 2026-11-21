package com.u1.slicer.presets

import androidx.annotation.StringRes

internal enum class FullPresetPrintPhase { CHECKING, READY, UPLOADING, STARTING, STARTED, UNCERTAIN, FAILED }

internal data class FullPresetPrintReview(
    val output: FullPresetOutput,
    val phase: FullPresetPrintPhase = FullPresetPrintPhase.CHECKING,
    val job: FullPresetPrintJob? = null,
    val report: FullPresetPrinterFilaments.Ready? = null,
    val usage: FullPresetGcodeInspection.Summary? = null,
    val presetNames: List<String> = output.presetNames,
    val checkedAtElapsedMillis: Long = 0,
    val progressPercent: Int? = null,
    @StringRes val message: Int? = null,
)
