package com.u1.slicer.printer

import com.u1.slicer.network.FilamentSlot
import com.u1.slicer.network.PrinterStatus
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadProgressTest {
    @Test
    fun progressOverloadDelegatesToLegacyTransportWithoutInventingProgress() = runBlocking {
        val result = TransportCommandResult.Success
        val transport = LegacyUploadTransport(result)
        var progressReported = false

        val actual = transport.uploadJob(File("unused.gcode"), "job.gcode") { _, _ ->
            progressReported = true
        }

        assertSame(result, actual)
        assertEquals(1, transport.uploadCalls)
        assertFalse(progressReported)
    }

    @Test
    fun progressOverloadPreservesLegacyFailure() = runBlocking {
        val result = TransportCommandResult.Failure("legacy upload failed")
        val transport = LegacyUploadTransport(result)

        val actual = transport.uploadJob(File("unused.gcode"), "job.gcode") { _, _ ->
            error("Legacy transport must not claim progress")
        }

        assertSame(result, actual)
        assertEquals(1, transport.uploadCalls)
    }

    @Test
    fun progressFractionIsGuardedAndClampedSeparatelyFromResponse() {
        assertEquals(0f, UploadProgress(0, 0).fraction, 0f)
        assertFalse(UploadProgress(0, 0).awaitingResponse)
        assertEquals(0f, UploadProgress(10, 0).fraction, 0f)
        assertFalse(UploadProgress(10, 0).awaitingResponse)

        val inFlight = UploadProgress(25, 100)
        assertEquals(0.25f, inFlight.fraction, 0f)
        assertFalse(inFlight.awaitingResponse)

        val bodyWritten = UploadProgress(100, 100)
        assertEquals(1f, bodyWritten.fraction, 0f)
        assertTrue(bodyWritten.awaitingResponse)

        val bodyWrittenWithRounding = UploadProgress(101, 100)
        assertEquals(1f, bodyWrittenWithRounding.fraction, 0f)
        assertTrue(bodyWrittenWithRounding.awaitingResponse)
    }

    @Test
    fun oldReportAndFinishCannotAffectNewAttempt() {
        val tracker = UploadProgressTracker()
        val oldAttempt = tracker.begin()
        tracker.report(oldAttempt, 20, 100)

        val newAttempt = tracker.begin()
        assertEquals(null, tracker.progress.value)
        tracker.report(newAttempt, 10, 50)
        tracker.report(oldAttempt, 90, 100)
        tracker.finish(oldAttempt)

        assertEquals(UploadProgress(10, 50), tracker.progress.value)
    }

    @Test
    fun reportAfterFinishIsIgnored() {
        val tracker = UploadProgressTracker()
        val attempt = tracker.begin()
        tracker.report(attempt, 100, 100)
        tracker.finish(attempt)

        assertEquals(null, tracker.progress.value)
        tracker.report(attempt, 100, 100)
        assertEquals(null, tracker.progress.value)
    }

    @Test
    fun normalProgressIsClearedWhenAttemptFinishes() {
        val tracker = UploadProgressTracker()
        val attempt = tracker.begin()
        tracker.report(attempt, 25, 100)

        assertEquals(UploadProgress(25, 100), tracker.progress.value)
        tracker.finish(attempt)
        assertEquals(null, tracker.progress.value)
    }

    /** Implements only the original overload to exercise PrinterTransport's compatibility default. */
    private class LegacyUploadTransport(
        private val result: TransportCommandResult,
    ) : PrinterTransport {
        override val capabilities = PrinterTransportCapabilities(supportsUpload = true)
        override val status: Flow<PrinterStatus> = emptyFlow()
        override val filamentSlots: Flow<List<FilamentSlot>> = emptyFlow()
        override val cameraState: Flow<CameraState> = emptyFlow()
        var uploadCalls = 0
            private set

        override suspend fun testConnection(): String? = null

        override suspend fun uploadJob(file: File, remoteName: String): TransportCommandResult {
            uploadCalls++
            return result
        }
    }
}
