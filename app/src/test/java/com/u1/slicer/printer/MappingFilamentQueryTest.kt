package com.u1.slicer.printer

import com.u1.slicer.network.FilamentSlot
import com.u1.slicer.network.PrinterStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MappingFilamentQueryTest {
    private class FakeTransport : PrinterTransport {
        override val capabilities = PrinterTransportCapabilities()
        override val status: Flow<PrinterStatus> = flowOf(PrinterStatus(state = "disconnected", progress = 0f))
        override val filamentSlots: Flow<List<FilamentSlot>> = flowOf(emptyList())
        override val cameraState: Flow<CameraState> = flowOf(CameraState.Disabled)
        var calls = 0
        var query: suspend () -> List<FilamentSlot>? = { null }
        override suspend fun testConnection(): String? = null
        override suspend fun queryFilamentSlots(): List<FilamentSlot>? { calls++; return query() }
    }

    @Test fun returnsFreshSlotsAndDoesNotQueryAnOldTransport() = runBlocking<Unit> {
        val slot = FilamentSlot(index = 0, label = "E1", color = "#112233", loaded = true, materialType = "PLA")
        val fake = FakeTransport().apply { query = { listOf(slot) } }
        assertNull(queryBoundMappingFilaments(fake, { false }))
        assertEquals(0, fake.calls)
        assertEquals(listOf(slot), queryBoundMappingFilaments(fake, { true }))
        assertEquals(1, fake.calls)
    }

    @Test fun switchDuringQueryDiscardsResult() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var current = true
        val fake = FakeTransport().apply {
            query = {
                entered.complete(Unit)
                release.await()
                listOf(FilamentSlot(index = 0, label = "E1", color = "#112233", loaded = true, materialType = "PLA"))
            }
        }
        val result = async { queryBoundMappingFilaments(fake, { current }) }
        entered.await()
        current = false
        release.complete(Unit)
        assertNull(result.await())
    }

    @Test fun timeoutCancelsInflightQueryAndDoesNotReturnStaleSlots() = runBlocking<Unit> {
        var cancelled = false
        val fake = FakeTransport().apply {
            query = { try { awaitCancellation() } finally { cancelled = true } }
        }
        assertNull(withTimeout(2_000) { queryBoundMappingFilaments(fake, { true }, timeoutMillis = 25) })
        assertEquals(1, fake.calls)
        assertTrue(cancelled)
        assertFalse(queryBoundMappingFilaments(fake, { false }) != null)
    }

    @Test fun callerCancellationPropagatesAndCancelsQuery() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        var cancelled = false
        val fake = FakeTransport().apply {
            query = { entered.complete(Unit); try { awaitCancellation() } finally { cancelled = true } }
        }
        val job = launch { queryBoundMappingFilaments(fake, { true }) }
        entered.await()
        withTimeout(2_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertTrue(cancelled)
    }
}
