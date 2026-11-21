package com.u1.slicer.presets

import java.io.IOException
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FullPresetGcodeInspectionTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun countsToolSelectionSupportWipeHeatersAndMacroTargetsAndHashesOriginalBytes() {
        val text = """
            ; T9 in this comment is ignored
            M104 S140 T0
            SM_PRINT_PREEXTRUDE_FILAMENT INDEX=1
            T2
            G1 X50 E0.5
            ; FEATURE: Support
            T3
            G1 X60 E0.8
            ; FEATURE: Wipe tower
            G3 X65 Y65 E0.1
            ; filament used [g] = 0.00, 0.00, 1.25, 0.41
            ; CONFIG_BLOCK_START
            ; filament_type = PLA;PETG;PLA;PLA
            ; CONFIG_BLOCK_END
        """.trimIndent().replace("\n", "\r\n")
        val bytes = text.toByteArray()
        val file = folder.newFile().apply { writeBytes(bytes) }

        val summary = FullPresetGcodeInspection.inspect(file)

        assertEquals(bytes.size.toLong(), summary.bytes)
        assertEquals(
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) },
            summary.sha256,
        )
        assertEquals(setOf(1, 2, 3, 4), summary.requiredSlots)
        assertEquals(listOf(0.0, 0.0, 1.25, 0.41), summary.gramsBySlot)
        assertEquals(listOf("PLA", "PETG", "PLA", "PLA"), summary.materialTypesBySlot)
    }

    @Test
    fun countsImplicitFirstHeadAndSeparatesFanIndexFromToolIndex() {
        val summary = inspect("M106 S255 P2\nG1 X1 E1\nSET_PRESSURE_ADVANCE ADVANCE=0.02 EXTRUDER=extruder2")
        assertEquals(setOf(1, 3), summary.requiredSlots)
        assertNull(summary.gramsBySlot)
        assertNull(summary.materialTypesBySlot)
    }

    @Test
    fun startupSwitchCheckRequiresAllHeads() {
        assertEquals(setOf(1, 2, 3, 4), inspect("T2\nSM_PRINT_CHECK_SWITCH_EXTRUDER\nG1 E1").requiredSlots)
    }

    @Test
    fun rejectsUnknownAndAmbiguousToolReferences() {
        listOf(
            "T4",
            "T-1",
            "M109 S140 T4",
            "M104 S200T3",
            "M104 S180 T1 T3",
            "SM_PRINT_AUTO_FEED EXTRUDER=4",
            "SM_PRINT_FLOW_CALIBRATE",
            "SM_PRINT_PREEXTRUDE_FILAMENT INDEX=-1",
            "UNRECOGNIZED_TOOL_MACRO",
        ).forEach { command ->
            assertThrows(command, IOException::class.java) { inspect("T0\nG1 E1\n$command") }
        }
    }

    @Test
    fun ignoresIncompleteNonfiniteNegativeAndDuplicateOptionalMetadata() {
        listOf(
            "1.00, 2.00",
            "0.00, Infinity, 2.00, 1.00",
            "0.00, NaN, 2.00, 1.00",
            "0.00, -1.00, 2.00, 1.00",
            "0.00, 1.00, 2.00, 1.00\n; filament used [g] = 0.00, 1.00, 2.00, 1.00",
        ).forEach { grams ->
            val result = inspect(
                "T0\nG1 E1\n; filament used [g] = $grams\n" +
                    "; CONFIG_BLOCK_START\n; filament_type = PLA;PLA\n; CONFIG_BLOCK_END",
            )
            assertEquals(setOf(1), result.requiredSlots)
            assertNull(result.gramsBySlot)
            assertNull(result.materialTypesBySlot)
        }
    }

    @Test
    fun rejectsExcessiveLinesIncompleteConfigAndGcodeWithoutExtrusion() {
        listOf("G1 X2", "G1 E1\n; CONFIG_BLOCK_START", "; " + "x".repeat(65_536)).forEach { text ->
            assertThrows(IOException::class.java) { inspect(text) }
        }
    }

    private fun inspect(text: String) = FullPresetGcodeInspection.inspect(folder.newFile().apply { writeText(text) })
}
