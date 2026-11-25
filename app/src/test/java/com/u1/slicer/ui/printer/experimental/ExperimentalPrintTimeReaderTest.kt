package com.u1.slicer.ui.printer.experimental

import com.u1.slicer.network.MoonrakerStatusSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentalPrintTimeReaderTest {
    private fun snapshot(name: String = "part.gcode", active: Double = 120.0, state: String = "printing") =
        MoonrakerStatusSnapshot.parse(JSONObject("""{"webhooks":{"state":"ready"},
            "print_stats":{"state":"$state","filename":"$name","print_duration":$active,"total_duration":600}}"""))

    @Test fun prefersPrinterEstimateAndOmitsFinishedJobs() = runBlocking {
        val reader = ExperimentalPrintTimeReader({ error("Metadata must not be read") })
        assertEquals(240.0, reader.remainingSeconds(snapshot().copy(remainingSeconds = 240.0)))
        assertEquals(0.0, reader.remainingSeconds(snapshot().copy(remainingSeconds = 0.0)))
        assertNull(reader.remainingSeconds(snapshot(state = "complete").copy(remainingSeconds = 240.0)))
        assertNull(reader.remainingSeconds(snapshot().copy(klippyState = "shutdown")))
    }

    @Test fun subtractsActiveTimeAndCachesMetadataForThirtySeconds() = runBlocking {
        var now = 0L
        var reads = 0
        val reader = ExperimentalPrintTimeReader({ reads++; 2880.0 }, { now })
        assertEquals(2760.0, reader.remainingSeconds(snapshot()))
        now = 29_999
        assertEquals(2700.0, reader.remainingSeconds(snapshot(active = 180.0)))
        assertEquals(1, reads)
        now = 30_000
        assertEquals(2700.0, reader.remainingSeconds(snapshot(active = 180.0)))
        assertEquals(2, reads)
        assertEquals(2700.0, reader.remainingSeconds(snapshot(active = 180.0, state = "paused")))
    }

    @Test fun changingOrRestartingJobsInvalidatesCachedMetadata() = runBlocking {
        val reads = mutableListOf<String>()
        val reader = ExperimentalPrintTimeReader({ reads.add(it); 600.0 }, { 0L })
        reader.remainingSeconds(snapshot("first.gcode"))
        reader.remainingSeconds(snapshot("second.gcode"))
        reader.remainingSeconds(snapshot("second.gcode", active = 1.0))
        reader.remainingSeconds(snapshot("second.gcode", active = 2.0))
        reader.remainingSeconds(snapshot("second.gcode", state = "complete"))
        reader.remainingSeconds(snapshot("second.gcode", active = 0.0))
        assertEquals(listOf("first.gcode", "second.gcode", "second.gcode", "second.gcode"), reads)
    }

    @Test fun missingInvalidAndOverrunEstimatesRemainUnknown() = runBlocking {
        for (total in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0, 90.0, 1e20)) {
            assertNull(ExperimentalPrintTimeReader({ total }).remainingSeconds(snapshot()))
        }
        val reader = ExperimentalPrintTimeReader({ error("No usable active time or filename") })
        assertNull(reader.remainingSeconds(snapshot().copy(activeSeconds = null)))
        assertNull(reader.remainingSeconds(snapshot().copy(activeSeconds = Double.NaN)))
        assertNull(reader.remainingSeconds(snapshot().copy(filename = null)))
    }

    @Test fun cancellingMetadataReadAllowsTheNextPollToRetry() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var reads = 0
        val reader = ExperimentalPrintTimeReader({
            reads++
            if (reads == 1) { entered.complete(Unit); awaitCancellation() }
            600.0
        })
        val job = launch { reader.remainingSeconds(snapshot()) }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(480.0, reader.remainingSeconds(snapshot()))
        assertEquals(2, reads)
    }
}
