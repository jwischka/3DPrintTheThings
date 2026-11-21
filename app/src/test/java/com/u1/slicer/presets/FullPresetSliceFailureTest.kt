package com.u1.slicer.presets

import com.u1.slicer.R
import org.junit.Assert.assertEquals
import org.junit.Test

class FullPresetSliceFailureTest {
    @Test
    fun missingFilamentSlotsShareOneActionableNotice() {
        for (detail in listOf(
            "Error: Model references an unavailable filament",
            "Error: Color paint references an unavailable filament",
        )) {
            assertEquals(
                FullPresetNotice(R.string.full_presets_slice_filament_slots, detail),
                fullPresetSliceFailure("  $detail\n"),
            )
        }
    }

    @Test
    fun distinguishesPaintLimitationsAndPreservesNativeDetails() {
        val details = mapOf(
            "Error: Geometry-only presets support color paint on filament slots 1 through 4" to
                R.string.full_presets_slice_color_paint_slots,
            "Error: Geometry-only presets do not support painted supports, seams, fuzzy skin or exterior facets" to
                R.string.full_presets_slice_other_paint_unsupported,
            "Error: Geometry-only presets do not support painted volumes" to
                R.string.full_presets_slice_paint_unsupported,
        )
        for ((detail, label) in details) {
            assertEquals(FullPresetNotice(label, detail), fullPresetSliceFailure(detail))
        }
    }

    @Test
    fun unrecognizedDiagnosticsKeepGenericNoticeAndBoundedDetails() {
        val unknown = "Error: Unable to map filament in the selected preset"
        val knownPrefixWithExtraWords = "Error: Model references an unavailable filament during initialization"
        for (detail in listOf(unknown, knownPrefixWithExtraWords, "x".repeat(600))) {
            assertEquals(
                FullPresetNotice(R.string.full_presets_slice_failed, detail.take(500)),
                fullPresetSliceFailure(detail),
            )
        }
        assertEquals(FullPresetNotice(R.string.full_presets_slice_failed), fullPresetSliceFailure(null))
        assertEquals(FullPresetNotice(R.string.full_presets_slice_failed), fullPresetSliceFailure("\n "))
    }
}
