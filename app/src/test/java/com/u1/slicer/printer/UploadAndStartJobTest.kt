package com.u1.slicer.printer

import com.u1.slicer.network.FilamentSlot
import com.u1.slicer.network.PrinterStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UploadAndStartJobTest {
    private val file = File("fixture.gcode")

    private class FakeTransport : PrinterTransport {
        override val capabilities = PrinterTransportCapabilities()
        override val status: Flow<PrinterStatus> = flowOf(PrinterStatus(state = "disconnected", progress = 0f))
        override val filamentSlots: Flow<List<FilamentSlot>> = flowOf(emptyList())
        override val cameraState: Flow<CameraState> = flowOf(CameraState.Disabled)
        val calls = mutableListOf<String>()
        var validate: suspend (Int) -> TransportCommandResult = { TransportCommandResult.Success }
        var upload: suspend () -> TransportCommandResult = { TransportCommandResult.Success }
        var start: suspend () -> TransportCommandResult = { TransportCommandResult.Success }
        private var checks = 0
        override suspend fun testConnection(): String? = null
        override suspend fun validateStartJob(): TransportCommandResult {
            calls += "validate"
            return validate(++checks)
        }
        // Deliberately override only the legacy two-argument overload.
        override suspend fun uploadJob(file: File, remoteName: String): TransportCommandResult {
            calls += "upload:$remoteName"
            return upload()
        }
        override suspend fun startJob(remoteName: String): TransportCommandResult {
            calls += "start:$remoteName"
            return start()
        }
        override suspend fun hintJobStarting() { calls += "hint" }
    }

    private suspend fun execute(fake: FakeTransport, current: () -> Boolean = { true }): TransportCommandResult =
        uploadAndStartJob(fake, file, "saved.gcode", { _, _ -> }, current)

    @Test fun successRunsBothChecksAndSupportsLegacyUploadOverload() = runBlocking<Unit> {
        val fake = FakeTransport()
        assertEquals(TransportCommandResult.Success, execute(fake))
        assertEquals(listOf("validate", "upload:saved.gcode", "validate", "start:saved.gcode", "hint"), fake.calls)
    }

    @Test fun preflightFailurePreventsUploadAndStateFlipPreventsStart() = runBlocking<Unit> {
        val failure = TransportCommandResult.Failure("printer busy")
        val beforeUpload = FakeTransport().apply { validate = { failure } }
        assertEquals(failure, execute(beforeUpload))
        assertEquals(listOf("validate"), beforeUpload.calls)

        val beforeStart = FakeTransport().apply { validate = { if (it == 2) failure else TransportCommandResult.Success } }
        assertEquals(failure, execute(beforeStart))
        assertEquals(listOf("validate", "upload:saved.gcode", "validate"), beforeStart.calls)
    }

    @Test fun uploadAndStartFailuresAreReturnedWithoutFurtherCalls() = runBlocking<Unit> {
        val uploadFailure = TransportCommandResult.Failure("upload failed")
        val fake = FakeTransport().apply { upload = { uploadFailure } }
        assertEquals(uploadFailure, execute(fake))
        assertEquals(listOf("validate", "upload:saved.gcode"), fake.calls)

        val startFailure = TransportCommandResult.Unsupported("start unavailable")
        val other = FakeTransport().apply { start = { startFailure } }
        assertEquals(startFailure, execute(other))
        assertEquals(listOf("validate", "upload:saved.gcode", "validate", "start:saved.gcode"), other.calls)
    }

    @Test fun printerSwitchDuringEachPreStartAwaitStopsSubsequentActions() = runBlocking<Unit> {
        for (stage in 1..3) {
            var current = true
            val fake = FakeTransport().apply {
                validate = { n -> if ((n == 1 && stage == 1) || (n == 2 && stage == 3)) current = false; TransportCommandResult.Success }
                upload = { if (stage == 2) current = false; TransportCommandResult.Success }
            }
            assertTrue(execute(fake) { current } is TransportCommandResult.Failure)
            assertTrue(fake.calls.none { it.startsWith("start:") })
            if (stage == 1) assertEquals(listOf("validate"), fake.calls)
        }
    }

    @Test fun switchAfterStartReportsUncertainOutcomeWithoutHinting() = runBlocking<Unit> {
        var current = true
        val fake = FakeTransport().apply { start = { current = false; TransportCommandResult.Success } }
        val result = execute(fake) { current } as TransportCommandResult.Failure
        assertTrue(result.reason.contains("check the printer"))
        assertTrue(!result.reason.contains("not started"))
        assertTrue("hint" !in fake.calls)
    }

    @Test fun cancellationDuringUncooperativeUploadNeverStartsPrint() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fake = FakeTransport().apply {
            upload = {
                started.complete(Unit)
                withContext(NonCancellable) { release.await() }
                TransportCommandResult.Success
            }
        }
        val job = launch { execute(fake) }
        started.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(listOf("validate", "upload:saved.gcode"), fake.calls)
    }
}
