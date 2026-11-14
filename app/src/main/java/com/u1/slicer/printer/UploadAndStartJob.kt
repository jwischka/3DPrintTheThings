package com.u1.slicer.printer

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** Preflight both before uploading and immediately before requesting a print start. */
internal suspend fun uploadAndStartJob(
    transport: PrinterTransport,
    file: File,
    remoteName: String,
    onProgress: (Long, Long) -> Unit,
    isCurrent: () -> Boolean,
): TransportCommandResult {
    suspend fun checkCurrent(startRequested: Boolean = false): TransportCommandResult.Failure? {
        currentCoroutineContext().ensureActive()
        return if (isCurrent()) null else TransportCommandResult.Failure(
            if (startRequested) "Active printer changed after the start request; check the printer for the job status"
            else "Active printer changed; the print was not started"
        )
    }

    checkCurrent()?.let { return it }
    val beforeUpload = transport.validateStartJob()
    checkCurrent()?.let { return it }
    if (beforeUpload !is TransportCommandResult.Success) return beforeUpload

    val uploaded = transport.uploadJob(file, remoteName, onProgress)
    checkCurrent()?.let { return it }
    if (uploaded !is TransportCommandResult.Success) return uploaded

    val beforeStart = transport.validateStartJob()
    checkCurrent()?.let { return it }
    if (beforeStart !is TransportCommandResult.Success) return beforeStart

    val started = transport.startJob(remoteName)
    checkCurrent(startRequested = true)?.let { return it }
    if (started !is TransportCommandResult.Success) return started
    transport.hintJobStarting()
    checkCurrent(startRequested = true)?.let { return it }
    return TransportCommandResult.Success
}
