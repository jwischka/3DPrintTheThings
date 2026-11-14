package com.u1.slicer.gcode

import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class U1StartMacroRemapTest {
    @Test
    fun `two canonical colors mapped to E3 E4 keep active start macros distinct`() {
        val assetPath = "src/main/assets/orca_profiles/printer/snapmaker_u1.json"
        val profileFile = File(assetPath).takeIf { it.isFile } ?: File("app/$assetPath")
        val profile = profileFile.readText()
        // The bundled profile has a duplicate unrelated key (printer_technology), which the
        // standalone JVM org.json rejects. Decode only this JSON string with the same tokenizer.
        val key = "\"machine_start_gcode\""
        val keyStart = profile.indexOf(key)
        require(keyStart >= 0 && keyStart == profile.lastIndexOf(key))
        val colon = profile.indexOf(':', keyStart + key.length)
        require(colon >= 0)
        val template = JSONTokener(profile.substring(colon + 1)).nextValue() as String
        // This tiny evaluator covers this fixed two-colour case, not native template expansion.
        val used = setOf(0, 1) // Guards for 2 and 3 are inactive.
        val mapping = mapOf(0 to 2, 1 to 3, 2 to 2, 3 to 3) // MainActivity's padded mapping.
        val temperatures = mapOf(0 to 210, 1 to 230)
        val firstLayerTemperatures = mapOf(0 to 215, 1 to 235)
        var active = true
        val lines = mutableListOf<String>()
        val guard = Regex("""\{if \(is_extruder_used\[(\d+)\]\)(?: and (\d+) != initial_extruder)?\}""")
        for (raw in template.lineSequence()) {
            val match = guard.matchEntire(raw.trim())
            if (match != null) {
                val index = match.groupValues[1].toInt()
                active = index in used && (match.groupValues[2].isEmpty() || index != 0)
                continue
            }
            if (raw.trim() == "{endif}") {
                active = true
                continue
            }
            if (!active || !raw.startsWith("SM_PRINT_")) continue
            val rendered = raw.replace("{initial_extruder}", "0")
                .replace(Regex("""\{nozzle_temperature\[(\d+)]\}""")) { matchResult ->
                    temperatures.getValue(matchResult.groupValues[1].toInt()).toString()
                }
                .replace(Regex("""\{nozzle_temperature_initial_layer\[(\d+)]\}""")) { matchResult ->
                    firstLayerTemperatures.getValue(matchResult.groupValues[1].toInt()).toString()
                }
                .replace("{nozzle_temperature_initial_layer[initial_extruder]}", "215")
            lines += GcodeToolRemapper.remapLine(rendered, mapping)
        }
        assertEquals(listOf("SM_PRINT_AUTO_FEED EXTRUDER=2", "SM_PRINT_AUTO_FEED EXTRUDER=3"),
            lines.filter { it.startsWith("SM_PRINT_AUTO_FEED") })
        assertEquals(listOf("SM_PRINT_FLOW_CALIBRATE INDEX=2 TARGET_TEMP=210",
            "SM_PRINT_FLOW_CALIBRATE INDEX=3 TARGET_TEMP=230"),
            lines.filter { it.startsWith("SM_PRINT_FLOW_CALIBRATE") })
        assertEquals(listOf("SM_PRINT_START_LINE INDEX=3 TARGET_TEMP=235",
            "SM_PRINT_START_LINE INDEX=2 TARGET_TEMP=215"),
            lines.filter { it.startsWith("SM_PRINT_START_LINE") })
    }
}
