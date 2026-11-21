package com.u1.slicer.presets

import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.Locale

/** Inspects locally sliced U1 G-code before offering a physical-tool dispatch. */
internal object FullPresetGcodeInspection {
    private const val TOOL_COUNT = 4
    private const val MAX_BYTES = 256L * 1024L * 1024L
    private const val MAX_LINE_BYTES = 64 * 1024

    data class Summary(
        val sha256: String,
        val bytes: Long,
        val requiredSlots: Set<Int>,
        val gramsBySlot: List<Double>?,
        val materialTypesBySlot: List<String>?,
    )

    @Throws(IOException::class)
    fun inspect(file: File): Summary {
        val digest = MessageDigest.getInstance("SHA-256")
        val inspection = Scanner()
        val chunk = ByteArray(32 * 1024)
        val line = ByteArray(MAX_LINE_BYTES)
        var lineSize = 0
        var bytes = 0L
        file.inputStream().use { input ->
            while (true) {
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedIOException("G-code inspection interrupted")
                }
                val count = input.read(chunk)
                if (count < 0) break
                bytes += count
                if (bytes > MAX_BYTES) throw IOException("G-code exceeds the inspection size limit")
                digest.update(chunk, 0, count)
                for (index in 0 until count) {
                    val byte = chunk[index]
                    if (byte == '\n'.code.toByte()) {
                        inspection.readLine(String(line, 0, lineSize, Charsets.UTF_8))
                        lineSize = 0
                    } else {
                        if (lineSize == line.size) throw IOException("G-code line exceeds the inspection size limit")
                        line[lineSize++] = byte
                    }
                }
            }
        }
        if (lineSize > 0) inspection.readLine(String(line, 0, lineSize, Charsets.UTF_8))
        if (!inspection.hasExtrusion || inspection.inConfigBlock) {
            throw IOException("G-code has no inspectable extrusion or an incomplete configuration block")
        }
        val requiredSlots = inspection.requiredSlots + inspection.gramsBySlot.orEmpty()
            .mapIndexedNotNull { index, grams -> (index + 1).takeIf { grams > 0 } }
        return Summary(
            sha256 = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) },
            bytes = bytes,
            requiredSlots = requiredSlots,
            gramsBySlot = inspection.gramsBySlot,
            materialTypesBySlot = inspection.materialTypesBySlot,
        )
    }

    private class Scanner {
        val requiredSlots = sortedSetOf<Int>()
        var gramsBySlot: List<Double>? = null
            private set
        var materialTypesBySlot: List<String>? = null
            private set
        var inConfigBlock = false
            private set
        var hasExtrusion = false
            private set
        private var activeTool = 0
        private var readGrams = false
        private var readMaterialTypes = false

        fun readLine(line: String) {
            val trimmed = line.trim()
            if (trimmed.startsWith(';')) {
                readComment(trimmed)
                return
            }
            val code = trimmed.substringBefore(';').trimEnd()
            if (code.isEmpty()) return
            val commandEnd = code.indexOfFirst(Char::isWhitespace).let { if (it < 0) code.length else it }
            val command = code.substring(0, commandEnd).uppercase(Locale.ROOT)
            val arguments = code.substring(commandEnd).trimStart()
            when (command) {
                "G0", "G1", "G2", "G3" -> {
                    // Motion arguments use single-letter fields. This includes wipe, support and purge moves.
                    if (EXTRUSION_WORD.containsMatchIn(arguments)) {
                        hasExtrusion = true
                        requireTool(activeTool)
                    }
                }
                "M104", "M109" -> readNumericCommandTool(arguments, defaultToActive = true)
                "M106", "M107", "M221" -> readNumericCommandTool(arguments, defaultToActive = false)
                "SM_PRINT_PREEXTRUDE_FILAMENT" -> readMacroTool(arguments, "INDEX", defaultToActive = false)
                "SM_PRINT_AUTO_FEED", "SM_PRINT_FLOW_CALIBRATE", "SM_PRINT_EXTRUDER_PREHEAT" ->
                    readMacroTool(arguments, "EXTRUDER", defaultToActive = false)
                "SET_PRESSURE_ADVANCE" -> readMacroTool(arguments, "EXTRUDER", defaultToActive = true)
                in ALL_HEAD_MACROS -> (0 until TOOL_COUNT).forEach(::requireTool)
                in ACTIVE_HEAD_MACROS -> requireTool(activeTool)
                in OTHER_U1_COMMANDS -> Unit
                else -> {
                    if (!TOOL_COMMAND.matches(command) || arguments.isNotEmpty()) {
                        throw IOException("G-code contains an unsupported command for physical-tool inspection")
                    }
                    activeTool = parseTool(command.substring(1))
                    requireTool(activeTool)
                }
            }
        }

        private fun readNumericCommandTool(arguments: String, defaultToActive: Boolean) {
            val words = arguments.split(WHITESPACE).filter(String::isNotEmpty)
            if (words.any { !NUMERIC_WORD.matches(it) }) {
                throw IOException("G-code contains an ambiguous numeric command")
            }
            val explicitTool = words.singleTool { it.startsWith("T", ignoreCase = true) }
            if (explicitTool != null) requireTool(parseTool(explicitTool.substring(1)))
            else if (defaultToActive) requireTool(activeTool)
        }

        private fun readComment(comment: String) {
            val entry = comment.substring(1).trimStart()
            when {
                entry == "CONFIG_BLOCK_START" -> {
                    if (inConfigBlock) throw IOException("G-code has nested configuration blocks")
                    inConfigBlock = true
                }
                entry == "CONFIG_BLOCK_END" -> inConfigBlock = false
                !inConfigBlock && entry.startsWith("filament used [g] =") -> {
                    val grams = entry.substringAfter('=').split(',').map { it.trim().toDoubleOrNull() }
                    gramsBySlot = grams.filterNotNull().takeIf {
                        !readGrams && it.size == TOOL_COUNT && grams.size == TOOL_COUNT &&
                            it.all { value -> value.isFinite() && value >= 0 }
                    }
                    readGrams = true
                }
                inConfigBlock && entry.startsWith("filament_type =") -> {
                    val materials = entry.substringAfter('=').split(';').map { it.trim().removeSurrounding("\"") }
                    materialTypesBySlot = materials.takeIf {
                        !readMaterialTypes && it.size == TOOL_COUNT && it.all(MATERIAL_TYPE::matches)
                    }
                    readMaterialTypes = true
                }
            }
        }

        private fun readMacroTool(arguments: String, field: String, defaultToActive: Boolean) {
            val tool = arguments.split(WHITESPACE)
                .singleTool { word -> word.startsWith("$field=", ignoreCase = true) }
                ?.substringAfter('=')
            if (tool == null) {
                if (!defaultToActive) throw IOException("G-code macro lacks an explicit physical tool")
                requireTool(activeTool)
            } else {
                val value = tool.lowercase(Locale.ROOT)
                val index = if (value.startsWith("extruder")) value.removePrefix("extruder").ifEmpty { "0" } else value
                requireTool(parseTool(index))
            }
        }

        private fun List<String>.singleTool(matches: (String) -> Boolean): String? {
            var tool: String? = null
            for (argument in this) {
                if (!matches(argument)) continue
                if (tool != null) throw IOException("G-code command specifies multiple physical tools")
                tool = argument
            }
            return tool
        }

        private fun parseTool(value: String): Int = value.toIntOrNull()
            ?.takeIf { it in 0 until TOOL_COUNT }
            ?: throw IOException("G-code references a tool outside the four physical U1 slots")

        private fun requireTool(tool: Int) {
            requiredSlots += tool + 1
        }
    }

    private val WHITESPACE = Regex("\\s+")
    private val TOOL_COMMAND = Regex("T[+-]?[0-9]+")
    private val EXTRUSION_WORD = Regex("(?:^|\\s)E[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?=\\s|$)", RegexOption.IGNORE_CASE)
    private val NUMERIC_WORD = Regex("[A-Za-z][+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")
    private val MATERIAL_TYPE = Regex("[A-Za-z0-9+_. -]{1,48}")

    // These U1 startup commands can operate on heads beyond the current selection.
    private val ALL_HEAD_MACROS = setOf("SM_PRINT_CHECK_SWITCH_EXTRUDER")
    private val ACTIVE_HEAD_MACROS = setOf(
        "FINELY_CLEAN_NOZZLE_STAGE_1", "FINELY_CLEAN_NOZZLE_STAGE_2",
        "MOVE_TO_DISCARD_FILAMENT_POSITION", "MOVE_TO_XY_IDLE_POSITION_EXTRUDER",
        "ROUGHLY_CLEAN_NOZZLE", "ROUGHLY_CLEAN_NOZZLE_WITH_DISCARD",
    )
    private val OTHER_U1_COMMANDS = setOf(
        "G4", "G17", "G21", "G28", "G90", "G91", "G92", "M73", "M82", "M83",
        "M140", "M190", "M204", "M220", "M400",
        "BED_MESH_CALIBRATE", "DEFECT_DETECT_NOODLE_FIRST", "DEFECT_DETECTION_DETECT",
        "DEFECT_DETECTION_DETECT_BED", "DEFECT_DETECTION_START", "DETECT_BED_PLATE",
        "PRINT_START", "PRINT_END", "SET_PRINT_STATS_INFO", "SET_VELOCITY_LIMIT",
        "TIMELAPSE_START", "TIMELAPSE_STOP", "TIMELAPSE_TAKE_FRAME",
    )
}
