package com.u1.slicer.ui.printer.experimental

import com.u1.slicer.network.MoonrakerStatusSnapshot

/** Metadata is cached per job; active printing time excludes warmup and pauses. */
internal class ExperimentalPrintTimeReader(
    private val readTotalSeconds: suspend (String) -> Double?,
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var filename: String? = null
    private var previousActiveSeconds: Double? = null
    private var checkedAt: Long? = null
    private var totalSeconds: Double? = null

    suspend fun remainingSeconds(snapshot: MoonrakerStatusSnapshot): Double? {
        if (snapshot.printState !in setOf("printing", "paused") ||
            (snapshot.klippyState != null && snapshot.klippyState != "ready")) {
            filename = null
            checkedAt = null
            return null
        }
        snapshot.remainingSeconds?.takeIf { it.isFinite() && it in 0.0..MAX_SECONDS }?.let { return it }
        val currentFile = snapshot.filename?.takeIf { it.isNotBlank() } ?: return null
        val active = snapshot.activeSeconds?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
        if (filename != currentFile || previousActiveSeconds?.let { active < it } == true) {
            filename = currentFile
            checkedAt = null
            totalSeconds = null
        }
        previousActiveSeconds = active
        val now = elapsedMillis()
        val age = checkedAt?.let { now - it }
        if (age == null || age !in 0L until 30_000L) {
            totalSeconds = readTotalSeconds(currentFile)?.takeIf { it.isFinite() && it in 0.0..MAX_SECONDS && it > 0.0 }
            checkedAt = elapsedMillis()
        }
        // An overrun is unknown, not a promise that an active job will finish now.
        return totalSeconds?.minus(active)?.takeIf { it > 0.0 }
    }

    private companion object {
        const val MAX_SECONDS = 365.0 * 24 * 60 * 60
    }
}
