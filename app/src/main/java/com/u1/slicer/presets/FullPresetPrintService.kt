package com.u1.slicer.presets

import android.os.SystemClock
import com.u1.slicer.network.MoonrakerClient
import com.u1.slicer.network.MoonrakerUploadResult
import com.u1.slicer.network.U1FilamentResult
import com.u1.slicer.network.U1PrintStartResult
import com.u1.slicer.ui.printer.experimental.ExperimentalPrinterConnection
import com.u1.slicer.ui.printer.experimental.PrinterConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

internal data class FullPresetPrintJob(
    val file: File,
    val filename: String,
    val confirmed: FullPresetPrinterFilaments.Ready,
    val physicalTools: Set<Int>,
    val sha256: String,
    val sizeBytes: Long,
    val materialTypesBySlot: List<String>? = null,
)

internal enum class FullPresetPrintFailure {
    INVALID_JOB,
    OUTPUT_CHANGED,
    CONNECTION_CHANGED,
    PRINTER_NOT_READY,
    FILAMENT_REPORT_UNAVAILABLE,
    FILAMENTS_CHANGED,
    MATERIAL_MISMATCH,
    UPLOAD_UNCONFIRMED,
    START_REJECTED,
    BUSY,
}

internal sealed interface FullPresetPrintProgress {
    data object Checking : FullPresetPrintProgress
    data class Uploading(val sentBytes: Long, val totalBytes: Long) : FullPresetPrintProgress
    data object Starting : FullPresetPrintProgress
}

internal sealed interface FullPresetPrintResult {
    data class Ready(val current: FullPresetPrinterFilaments.Ready) : FullPresetPrintResult {
        val checkedAtElapsedMillis: Long get() = current.snapshot.receivedAtElapsedMillis
    }
    data class Failed(val reason: FullPresetPrintFailure) : FullPresetPrintResult
    data object Started : FullPresetPrintResult
    data object Uncertain : FullPresetPrintResult
}

internal class FullPresetPrintService(
    private val clientIfCurrent: (PrinterConnection) -> MoonrakerClient?,
    private val isCurrent: (PrinterConnection) -> Boolean,
    private val nowElapsedMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    constructor(connection: ExperimentalPrinterConnection) : this(connection::clientIfCurrent, connection::isCurrent)

    suspend fun review(job: FullPresetPrintJob): FullPresetPrintResult = withContext(Dispatchers.IO) {
        if (!valid(job)) return@withContext failed(FullPresetPrintFailure.INVALID_JOB)
        if (!outputMatches(job)) return@withContext failed(FullPresetPrintFailure.OUTPUT_CHANGED)
        val client = clientIfCurrent(job.confirmed.connection)
            ?: return@withContext failed(FullPresetPrintFailure.CONNECTION_CHANGED)
        checkPrinter(client, job)
    }

    suspend fun send(
        job: FullPresetPrintJob,
        onProgress: (FullPresetPrintProgress) -> Unit,
    ): FullPresetPrintResult = withContext(Dispatchers.IO) {
        if (!sendLock.tryLock()) return@withContext failed(FullPresetPrintFailure.BUSY)
        try {
            sendOnce(job, onProgress)
        } finally {
            sendLock.unlock()
        }
    }

    private suspend fun sendOnce(
        job: FullPresetPrintJob,
        onProgress: (FullPresetPrintProgress) -> Unit,
    ): FullPresetPrintResult {
        onProgress(FullPresetPrintProgress.Checking)
        if (!valid(job)) return failed(FullPresetPrintFailure.INVALID_JOB)
        if (!outputMatches(job)) return failed(FullPresetPrintFailure.OUTPUT_CHANGED)
        val connection = job.confirmed.connection
        val client = clientIfCurrent(connection) ?: return failed(FullPresetPrintFailure.CONNECTION_CHANGED)
        val beforeUpload = checkPrinter(client, job)
        if (beforeUpload !is FullPresetPrintResult.Ready) return beforeUpload

        val remoteName = remoteFilename(job.filename)
        val upload = client.uploadPreparedGcode(job.file, remoteName, job.sizeBytes, job.sha256) { sent, total ->
            onProgress(FullPresetPrintProgress.Uploading(sent, total))
        }
        currentCoroutineContext().ensureActive()
        if (!isCurrent(connection)) return failed(FullPresetPrintFailure.CONNECTION_CHANGED)
        if (upload != MoonrakerUploadResult.Success) return failed(FullPresetPrintFailure.UPLOAD_UNCONFIRMED)
        onProgress(FullPresetPrintProgress.Checking)
        if (!outputMatches(job)) return failed(FullPresetPrintFailure.OUTPUT_CHANGED)
        val afterUpload = checkPrinter(client, job)
        if (afterUpload !is FullPresetPrintResult.Ready) return afterUpload

        onProgress(FullPresetPrintProgress.Starting)
        val started = client.startPreparedU1Print(remoteName) {
            isCurrent(connection) && afterUpload.current.snapshot.isFresh(nowElapsedMillis())
        }
        return when (started) {
            U1PrintStartResult.Started -> if (isCurrent(connection)) FullPresetPrintResult.Started
                else FullPresetPrintResult.Uncertain
            U1PrintStartResult.Uncertain -> FullPresetPrintResult.Uncertain
            U1PrintStartResult.Rejected -> failed(FullPresetPrintFailure.START_REJECTED)
            U1PrintStartResult.NotSent -> when {
                !isCurrent(connection) -> failed(FullPresetPrintFailure.CONNECTION_CHANGED)
                !afterUpload.current.snapshot.isFresh(nowElapsedMillis()) -> failed(FullPresetPrintFailure.FILAMENTS_CHANGED)
                else -> failed(FullPresetPrintFailure.START_REJECTED)
            }
        }
    }

    private suspend fun checkPrinter(client: MoonrakerClient, job: FullPresetPrintJob): FullPresetPrintResult {
        val connection = job.confirmed.connection
        if (!isCurrent(connection)) return failed(FullPresetPrintFailure.CONNECTION_CHANGED)
        val ready = client.validateU1StartJob() == null
        if (!isCurrent(connection)) return failed(FullPresetPrintFailure.CONNECTION_CHANGED)
        if (!ready) return failed(FullPresetPrintFailure.PRINTER_NOT_READY)
        val report = client.readU1FilamentSnapshot()
        if (!isCurrent(connection)) return failed(FullPresetPrintFailure.CONNECTION_CHANGED)
        if (report !is U1FilamentResult.Received || !report.snapshot.isFresh(nowElapsedMillis())) {
            return failed(FullPresetPrintFailure.FILAMENT_REPORT_UNAVAILABLE)
        }
        val current = FullPresetPrinterFilaments.Ready(connection, report.snapshot)
        if (!current.hasLoadedTargets(job.physicalTools) || current.snapshot.slots != job.confirmed.snapshot.slots) {
            return failed(FullPresetPrintFailure.FILAMENTS_CHANGED)
        }
        val materialTypes = job.materialTypesBySlot
        if (materialTypes == null || materialTypes.size != fullPresetPhysicalSlots.count() ||
            job.physicalTools.any { slot ->
                val sliced = materialTypes[slot - 1].trim()
                val loaded = current.snapshot.slots[slot - 1].material?.trim()
                sliced.isEmpty() || sliced.equals("unknown", ignoreCase = true) ||
                    !sliced.equals(loaded, ignoreCase = true)
            }) {
            return failed(FullPresetPrintFailure.MATERIAL_MISMATCH)
        }
        return FullPresetPrintResult.Ready(current)
    }

    private fun valid(job: FullPresetPrintJob): Boolean =
        job.sizeBytes in 1..MAX_GCODE_BYTES && job.sha256.matches(sha256Pattern) &&
            job.filename.length in 7..140 && job.filename.endsWith(".gcode", ignoreCase = true) &&
            job.filename.none { it.isISOControl() || it == '/' || it == '\\' } &&
            job.physicalTools.isNotEmpty() && job.physicalTools.all { it in fullPresetPhysicalSlots } &&
            job.confirmed.snapshot.slots.map { it.physicalSlot } == fullPresetPhysicalSlots.toList() &&
            job.confirmed.hasLoadedTargets(job.physicalTools)

    private suspend fun outputMatches(job: FullPresetPrintJob): Boolean {
        if (!job.file.isFile || job.file.length() != job.sizeBytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        val context = currentCoroutineContext()
        var bytes = 0L
        try {
            job.file.inputStream().use { source ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    context.ensureActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    bytes += count
                    if (bytes > job.sizeBytes) return false
                    digest.update(buffer, 0, count)
                }
            }
        } catch (_: IOException) {
            return false
        }
        return bytes == job.sizeBytes && digest.digest().joinToString("") { "%02x".format(it) } == job.sha256
    }

    private fun remoteFilename(filename: String): String {
        val stem = filename.dropLast(".gcode".length)
            .replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_', '-').take(64).ifEmpty { "print" }
        return "$stem-${UUID.randomUUID()}.gcode"
    }

    private fun failed(reason: FullPresetPrintFailure) = FullPresetPrintResult.Failed(reason)

    private companion object {
        const val MAX_GCODE_BYTES = 256L * 1024 * 1024
        val sha256Pattern = Regex("[0-9a-f]{64}")
        val sendLock = Mutex()
    }
}

