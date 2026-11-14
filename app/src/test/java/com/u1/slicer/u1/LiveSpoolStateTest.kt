package com.u1.slicer.u1

import com.u1.slicer.data.CanonicalFilamentList
import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.data.FilamentEntry
import com.u1.slicer.data.FilamentSource
import com.u1.slicer.network.FilamentSlot
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveSpoolStateTest {
    private val presets = (0..3).map { ExtruderPreset(it, "#AAAAAA", "PLA") }
    private val canonical = CanonicalFilamentList(listOf(
        FilamentEntry(0, "#0000FF", "PLA", FilamentSource.FILE_COLOUR),
        FilamentEntry(1, "#FF0000", "PLA", FilamentSource.FILE_COLOUR),
    ))

    @Test fun `suggestions use loaded sparse physical indices`() {
        val slots = listOf(
            FilamentSlot(0, "E1", "#0000FF", false, "PLA"),
            FilamentSlot(2, "E3", "#FF0000", true, "PETG"),
            FilamentSlot(3, "E4", "#0000FF", true, "PLA"),
        )
        assertEquals(listOf(3, 2), suggestLiveSpoolMapping(canonical, presets, slots, listOf(0, 1)))
    }

    @Test fun `unknown and all empty data preserve fallback mapping`() {
        val fallback = listOf(1, 0)
        assertEquals(fallback, suggestLiveSpoolMapping(canonical, presets,
            listOf(FilamentSlot(2, "E3", "#FF0000", false, "PETG")), fallback))
        assertEquals(fallback, suggestLiveSpoolMapping(canonical, presets,
            listOf(FilamentSlot(3, "E4", "unknown", true, "PLA")), fallback))
        assertEquals(fallback, suggestLiveSpoolMapping(canonical, presets,
            listOf(FilamentSlot(99, "E100", "#FF0000", true, "PLA")), fallback))
    }

    @Test fun `display overlay uses live values and marks missing values unknown`() {
        val display = liveDisplayPresets(presets, listOf(
            FilamentSlot(0, "E1", "#123456", true, "PETG"),
            FilamentSlot(1, "E2", "#999999", false, "ABS"),
            FilamentSlot(3, "E4", "invalid", true, "Unknown"),
        ))
        assertEquals("#123456", display[0].color)
        assertEquals("PETG", display[0].materialType)
        assertEquals("#808080", display[1].color)
        assertEquals("", display[1].materialType)
        assertEquals("#808080", display[2].color)
        assertEquals("", display[2].materialType)
        assertEquals("#808080", display[3].color)
        assertEquals("", display[3].materialType)
    }

    @Test fun `empty and material mismatch warnings need known live data`() {
        assertEquals(LiveSlotWarning.EMPTY, liveSlotWarning(FilamentSlot(0, "E1", "#FFFFFF", false, ""), "PLA"))
        assertEquals(LiveSlotWarning.MATERIAL_MISMATCH,
            liveSlotWarning(FilamentSlot(1, "E2", "#FFFFFF", true, "PETG"), "PLA"))
        assertEquals(null, liveSlotWarning(FilamentSlot(1, "E2", "#FFFFFF", true, "PLA"), "pla"))
        assertEquals(null, liveSlotWarning(FilamentSlot(1, "E2", "#FFFFFF", true, "Unknown"), "PLA"))
        assertEquals(null, liveSlotWarning(null, "PLA"))
    }
}
