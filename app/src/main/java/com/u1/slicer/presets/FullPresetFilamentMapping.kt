package com.u1.slicer.presets

import com.u1.slicer.network.U1FilamentSnapshot
import com.u1.slicer.ui.printer.experimental.PrinterConnection

internal val fullPresetPhysicalSlots = 1..4

internal data class FullPresetModelFilaments(val usedSlots: Set<Int>, val colors: List<String>) {
    val canMap get() = usedSlots.isNotEmpty() && usedSlots.all { it in fullPresetPhysicalSlots }

    fun color(slot: Int): String? = colors.getOrNull(slot - 1)?.takeIf(String::isNotBlank)

    fun positionalMapping(targets: Map<Int, Int>): List<Int>? {
        if (!canMap || targets.keys != usedSlots || targets.values.any { it !in fullPresetPhysicalSlots }) return null
        val firstUsedTarget = targets.getValue(usedSlots.min())
        return (1..usedSlots.max()).map { targets[it] ?: firstUsedTarget }
    }
}

internal sealed interface FullPresetPrinterFilaments {
    data object Unchecked : FullPresetPrinterFilaments
    data object NoConnection : FullPresetPrinterFilaments
    data class Unavailable(val lastReady: Ready? = null) : FullPresetPrinterFilaments
    data class Ready(val connection: PrinterConnection, val snapshot: U1FilamentSnapshot) : FullPresetPrinterFilaments {
        fun hasLoadedTargets(targets: Collection<Int>): Boolean = targets.all { target ->
            snapshot.slots.any { it.physicalSlot == target && it.present == true }
        }
    }

    val displayedReport: Ready? get() = when (this) {
        is Ready -> this
        is Unavailable -> lastReady
        else -> null
    }
}

internal data class FullPresetAppliedFilamentMapping(
    val targets: Map<Int, Int>,
    val positionalMapping: List<Int>,
    val physicalPresets: List<String>,
    val confirmed: FullPresetPrinterFilaments.Ready,
) {
    // A selected process can use a physical tool outside the model's assignments.
    fun matches(fresh: FullPresetPrinterFilaments.Ready): Boolean =
        fresh.connection == confirmed.connection && fresh.hasLoadedTargets(targets.values) &&
            fresh.snapshot.slots.sortedBy { it.physicalSlot } == confirmed.snapshot.slots.sortedBy { it.physicalSlot }
}
