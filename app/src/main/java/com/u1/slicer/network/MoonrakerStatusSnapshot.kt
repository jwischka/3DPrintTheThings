package com.u1.slicer.network

import org.json.JSONObject

internal sealed interface MoonrakerStatusResult {
    data class Received(val snapshot: MoonrakerStatusSnapshot) : MoonrakerStatusResult
    data class Unavailable(val problem: MoonrakerJsonRead.Problem, val httpCode: Int? = null) :
        MoonrakerStatusResult
}

internal data class MoonrakerHeaterReading(
    val temperature: Float?,
    val target: Float?,
)

/** Missing and invalid metrics remain absent, while reported zeroes are retained. */
internal data class MoonrakerStatusSnapshot(
    val klippyState: String?,
    val printState: String?,
    val filename: String?,
    val displayProgress: Float?,
    val fileProgress: Float?,
    val printProgress: Float?,
    val elapsedSeconds: Double?,
    val activeSeconds: Double?,
    val remainingSeconds: Double?,
    val filamentUsed: Float?,
    val currentLayer: Int?,
    val totalLayers: Int?,
    val activeExtruder: String?,
    val bed: MoonrakerHeaterReading?,
    val extruders: Map<Int, MoonrakerHeaterReading>,
) {
    // Klipper's display value follows the slicer's M73 progress when available.
    val progress: Float? get() = displayProgress ?: fileProgress ?: printProgress

    fun asLegacyStatus() = PrinterStatus(
        state = printState ?: "standby",
        progress = fileProgress ?: printProgress ?: 0f,
        filename = filename.orEmpty(),
        printDuration = activeSeconds?.toFloat() ?: 0f,
        filamentUsed = filamentUsed ?: 0f,
        nozzleTemp = extruders[0]?.temperature ?: 0f,
        nozzleTarget = extruders[0]?.target ?: 0f,
        bedTemp = bed?.temperature ?: 0f,
        bedTarget = bed?.target ?: 0f,
        extruders = extruders.map { (index, reading) ->
            val target = reading.target ?: 0f
            ExtruderStatus(index, reading.temperature ?: 0f, target, target > 0f)
        },
    )

    companion object {
        const val COMMON_OBJECTS = "print_stats&virtual_sdcard&heater_bed&extruder&extruder1&extruder2&extruder3"

        fun parse(status: JSONObject): MoonrakerStatusSnapshot {
            val stats = status.optJSONObject("print_stats")
            val display = status.optJSONObject("display_status")
            val layers = stats?.optJSONObject("info")
            val extruders = buildMap {
                for (index in 0..3) {
                    val name = if (index == 0) "extruder" else "extruder$index"
                    status.optJSONObject(name)?.let { put(index, heater(it)) }
                }
            }
            return MoonrakerStatusSnapshot(
                klippyState = status.optJSONObject("webhooks").text("state"),
                printState = stats.text("state"),
                filename = stats.text("filename"),
                displayProgress = display.progress(),
                fileProgress = status.optJSONObject("virtual_sdcard").progress(),
                printProgress = stats.progress(),
                elapsedSeconds = stats.number("total_duration")?.takeIf { it >= 0.0 },
                activeSeconds = stats.number("print_duration")?.takeIf { it >= 0.0 },
                remainingSeconds = stats.number("remaining_time")?.takeIf { it >= 0.0 },
                filamentUsed = stats.number("filament_used")?.toFloat(),
                currentLayer = layers.count("current_layer"),
                totalLayers = layers.count("total_layer"),
                activeExtruder = status.optJSONObject("toolhead").text("extruder"),
                bed = status.optJSONObject("heater_bed")?.let(::heater),
                extruders = extruders,
            )
        }

        private fun heater(value: JSONObject) = MoonrakerHeaterReading(
            temperature = value.number("temperature")?.toFloat()?.takeIf { it.isFinite() },
            target = value.number("target")?.toFloat()?.takeIf { it.isFinite() },
        )

        private fun JSONObject?.text(key: String): String? =
            (this?.opt(key) as? String)?.takeIf { it.isNotBlank() }

        private fun JSONObject?.number(key: String): Double? =
            this?.optDouble(key)?.takeIf { it.isFinite() }

        private fun JSONObject?.count(key: String): Int? =
            number(key)?.takeIf { it in 0.0..Int.MAX_VALUE.toDouble() && it % 1.0 == 0.0 }?.toInt()

        private fun JSONObject?.progress(): Float? =
            number("progress")?.takeIf { it in 0.0..1.0 }?.toFloat()
    }
}
