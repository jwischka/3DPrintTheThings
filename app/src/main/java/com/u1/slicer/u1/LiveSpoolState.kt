package com.u1.slicer.u1

import com.u1.slicer.data.CanonicalFilamentList
import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.network.FilamentSlot

sealed interface LiveSpoolState {
    data object Loading : LiveSpoolState
    data object Unavailable : LiveSpoolState
    data class Ready(val slots: List<FilamentSlot>) : LiveSpoolState
}

enum class LiveSlotWarning { EMPTY, MATERIAL_MISMATCH }

/** Live colors/materials are display-only; the saved presets still describe slice-time settings. */
fun liveDisplayPresets(presets: List<ExtruderPreset>, slots: List<FilamentSlot>): List<ExtruderPreset> {
    val liveByIndex = slots.associateBy { it.index }
    return presets.map { preset ->
        val live = liveByIndex[preset.index]?.takeIf { it.loaded }
        preset.copy(
            color = live?.color?.takeIf { parseRgb(it) != null } ?: "#808080",
            materialType = live?.materialType?.takeIf { it.isKnownMaterial() }.orEmpty(),
        )
    }
}

/** Preserve each candidate's physical E-slot index, even when available slots are sparse. */
fun suggestLiveSpoolMapping(
    canonicalList: CanonicalFilamentList,
    presets: List<ExtruderPreset>,
    slots: List<FilamentSlot>,
    fallback: List<Int>,
): List<Int> {
    val indices = presets.mapTo(mutableSetOf()) { it.index }
    val candidates = slots.filter { it.loaded && it.index in indices && parseRgb(it.color) != null }
    if (candidates.isEmpty()) return fallback
    return canonicalList.filaments.mapIndexed { row, filament ->
        val target = parseRgb(filament.color)
        if (target == null) fallback.getOrNull(row) ?: presets.firstOrNull()?.index ?: 0
        else candidates.minWithOrNull(compareBy<FilamentSlot> { distanceSquared(target, parseRgb(it.color)!!) }
            .thenBy { it.index })!!.index
    }
}

fun liveSlotWarning(slot: FilamentSlot?, slicedMaterial: String?): LiveSlotWarning? {
    if (slot == null) return null
    if (!slot.loaded) return LiveSlotWarning.EMPTY
    if (!slot.materialType.isKnownMaterial() || !slicedMaterial.isKnownMaterial()) return null
    return if (slot.materialType.trim().equals(slicedMaterial!!.trim(), ignoreCase = true)) null
    else LiveSlotWarning.MATERIAL_MISMATCH
}

private fun String?.isKnownMaterial(): Boolean {
    val value = this?.trim().orEmpty()
    return value.isNotEmpty() && !value.equals("unknown", true) && !value.equals("empty", true)
}

private fun parseRgb(color: String): IntArray? {
    val hex = color.removePrefix("#")
    if (hex.length != 6 || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    return intArrayOf(
        hex.substring(0, 2).toInt(16),
        hex.substring(2, 4).toInt(16),
        hex.substring(4, 6).toInt(16),
    )
}

private fun distanceSquared(a: IntArray, b: IntArray): Int =
    (a[0] - b[0]) * (a[0] - b[0]) +
        (a[1] - b[1]) * (a[1] - b[1]) +
        (a[2] - b[2]) * (a[2] - b[2])
