package com.u1.slicer.presets

import com.u1.slicer.network.U1FilamentSlot
import com.u1.slicer.network.U1FilamentSnapshot
import com.u1.slicer.ui.printer.experimental.PrinterConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FullPresetFilamentMappingTest {
    private val connection = PrinterConnection("http://127.0.0.1:7125")
    private val white = U1FilamentSlot(2, true, "Snapmaker", "PLA", "Matte", "#FFFFFF")
    private val blue = white.copy(physicalSlot = 3, color = "#4488FF")
    private val empty = U1FilamentSlot(1, false, null, null, null, null)
    private val unknown = empty.copy(physicalSlot = 4, present = null)

    @Test
    fun mapsSourceSlotsInOrderAndAllowsAnExplicitSharedTool() {
        val filaments = FullPresetModelFilaments(setOf(1, 2), listOf("#4488FF", "#FFFFFF"))
        assertEquals("#FFFFFF", filaments.color(2))
        assertEquals(listOf(3, 2), filaments.positionalMapping(mapOf(2 to 2, 1 to 3)))
        assertEquals(listOf(2, 2), filaments.positionalMapping(mapOf(1 to 2, 2 to 2)))
        assertNull(filaments.positionalMapping(mapOf(1 to 3)))
        assertNull(filaments.positionalMapping(mapOf(1 to 3, 2 to 5)))
        assertFalse(FullPresetModelFilaments(setOf(1, 5), emptyList()).canMap)
    }

    @Test
    fun manifestFillsUnusedSourcePositionsFromAnExplicitTarget() {
        val filaments = FullPresetModelFilaments(setOf(1, 3), emptyList())
        assertEquals(listOf(3, 3, 2), filaments.positionalMapping(mapOf(1 to 3, 3 to 2)))
    }

    @Test
    fun recheckRejectsChangedPrinterSpoolAndUnconfirmedPresence() {
        val slots = listOf(empty, white, blue, unknown)
        val confirmed = report(slots)
        val mapping = FullPresetAppliedFilamentMapping(mapOf(1 to 3, 2 to 2), listOf(3, 2), emptyList(), confirmed)
        assertTrue(mapping.matches(confirmed.copy(snapshot = U1FilamentSnapshot(slots.reversed(), 100L, 100L))))
        assertFalse(mapping.matches(report(listOf(empty, white, blue.copy(present = null), unknown))))
        assertFalse(mapping.matches(report(listOf(empty, white, blue.copy(present = false), unknown))))
        assertFalse(mapping.matches(report(listOf(empty, white, blue.copy(subtype = "Basic"), unknown))))
        assertFalse(mapping.matches(report(listOf(empty, white, blue.copy(color = "#AABBCC"), unknown))))
        assertFalse(mapping.matches(report(listOf(empty.copy(present = true), white, blue, unknown))))
        assertFalse(mapping.matches(confirmed.copy(connection = connection.copy(revision = connection.revision + 1))))
    }

    private fun report(slots: List<U1FilamentSlot>) =
        FullPresetPrinterFilaments.Ready(connection, U1FilamentSnapshot(slots, 10L, 10L))
}
