package com.u1.slicer.printer

import com.u1.slicer.network.FilamentSlot
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** Returns only a fresh query result for the still-current transport. */
internal suspend fun queryBoundMappingFilaments(
    transport: PrinterTransport,
    isCurrent: () -> Boolean,
    timeoutMillis: Long = 8_000,
): List<FilamentSlot>? {
    currentCoroutineContext().ensureActive()
    if (!isCurrent()) return null
    return withTimeoutOrNull(timeoutMillis) {
        val slots = try { transport.queryFilamentSlots() } catch (_: IOException) { null }
        currentCoroutineContext().ensureActive()
        if (isCurrent()) slots?.takeIf { it.isNotEmpty() } else null
    }
}
