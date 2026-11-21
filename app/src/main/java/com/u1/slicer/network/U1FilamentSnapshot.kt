package com.u1.slicer.network

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal data class U1FilamentSlot(
    val physicalSlot: Int,
    val present: Boolean?,
    val vendor: String?,
    val material: String?,
    val subtype: String?,
    val color: String?,
)

internal sealed interface U1FilamentResult {
    data class Received(val snapshot: U1FilamentSnapshot) : U1FilamentResult

    /** No problem means the printer does not expose the U1 filament fields. */
    data class Unavailable(val problem: MoonrakerJsonRead.Problem? = null, val httpCode: Int? = null) :
        U1FilamentResult
}

internal data class U1FilamentSnapshot(
    val slots: List<U1FilamentSlot>,
    val receivedAtMillis: Long,
    val receivedAtElapsedMillis: Long,
) {
    fun isFresh(nowElapsedMillis: Long, maxAgeMillis: Long = 15_000): Boolean =
        nowElapsedMillis >= receivedAtElapsedMillis &&
            nowElapsedMillis - receivedAtElapsedMillis <= maxAgeMillis

    companion object {
        const val OBJECT_QUERY =
            "print_task_config=filament_exist,filament_vendor,filament_type,filament_sub_type,filament_color_rgba"

        private val hexColor = Regex("[0-9a-fA-F]{6}(?:[0-9a-fA-F]{2})?")
        private val unknownMetadata = setOf("none", "unknown", "empty", "n/a")

        fun parse(
            status: JSONObject,
            receivedAtMillis: Long,
            receivedAtElapsedMillis: Long,
        ): U1FilamentSnapshot? {
            val fields = status.optJSONObject("print_task_config") ?: return null
            val presence = fields.optJSONArray("filament_exist")
            val vendors = fields.optJSONArray("filament_vendor")
            val materials = fields.optJSONArray("filament_type")
            val subtypes = fields.optJSONArray("filament_sub_type")
            val colors = fields.optJSONArray("filament_color_rgba")
            if (presence == null && vendors == null && materials == null && subtypes == null && colors == null) {
                return null
            }
            val slots = List(4) { index ->
                U1FilamentSlot(
                    physicalSlot = index + 1,
                    present = parsePresence(presence?.opt(index)),
                    vendor = vendors.metadata(index),
                    material = materials.metadata(index),
                    subtype = subtypes.metadata(index),
                    color = (colors?.opt(index) as? String)?.trim()?.removePrefix("#")
                        ?.takeIf(hexColor::matches)?.take(6)?.uppercase(Locale.ROOT)?.let { "#$it" },
                )
            }
            return U1FilamentSnapshot(slots, receivedAtMillis, receivedAtElapsedMillis)
        }

        private fun JSONArray?.metadata(index: Int): String? =
            (this?.opt(index) as? String)?.trim()?.takeIf {
                it.length in 1..80 && it.none(Char::isISOControl) && it.lowercase(Locale.ROOT) !in unknownMetadata
            }

        private fun parsePresence(value: Any?): Boolean? = when (value) {
            is Boolean -> value
            is Number -> when (value.toDouble()) {
                0.0 -> false
                1.0 -> true
                else -> null
            }
            is String -> when (value.trim().lowercase(Locale.ROOT)) {
                "false", "0" -> false
                "true", "1" -> true
                else -> null
            }
            else -> null
        }
    }
}
