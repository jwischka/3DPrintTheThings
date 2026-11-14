package com.u1.slicer.printer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** File payload written to the connection, not confirmation that the server accepted it. */
data class UploadProgress(val sentBytes: Long, val totalBytes: Long) {
    val fraction: Float
        get() = if (totalBytes > 0) (sentBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f) else 0f
    val awaitingResponse: Boolean
        get() = totalBytes > 0 && sentBytes >= totalBytes
}

/** Keeps late callbacks and cleanup from a canceled upload out of the next upload's UI. */
internal class UploadProgressTracker {
    private var attempt = 0L
    private val state = MutableStateFlow<UploadProgress?>(null)
    val progress: StateFlow<UploadProgress?> = state.asStateFlow()

    @Synchronized fun begin(): Long {
        attempt++
        state.value = null
        return attempt
    }

    @Synchronized fun report(id: Long, sent: Long, total: Long) {
        if (id == attempt) state.value = UploadProgress(sent, total)
    }

    @Synchronized fun finish(id: Long) {
        if (id == attempt) begin()
    }
}
