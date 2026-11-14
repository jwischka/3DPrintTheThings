package com.u1.slicer.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.printer.MoonrakerTransport
import com.u1.slicer.printer.TransportCommandResult
import com.u1.slicer.printer.uploadAndStartJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Real Android client against an in-memory Linux CI fixture, never printer hardware. */
@RunWith(AndroidJUnit4::class)
class SimulatedU1InstrumentedTest {
    private val base = "http://10.0.2.2:18725"
    private val http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
    private fun client(key: String? = "simulator-test-key") = MoonrakerClient().apply {
        baseUrl = base
        configureApiKey(key)
    }

    private fun control(path: String, value: JSONObject? = null): JSONObject {
        val request = Request.Builder().url("$base/__sim/$path")
            .header("X-Simulator-Control", "simulator-control")
        if (value != null) request.post(value.toString().toRequestBody("application/json".toMediaType()))
        return http.newCall(request.build()).execute().use {
            check(it.isSuccessful) { "Simulator control failed: HTTP ${it.code}" }
            JSONObject(it.body!!.string())
        }
    }

    @Before fun verifyFixtureAndReset() {
        // Fail before any mutation if the fixed emulator-host address is not our fixture.
        val marker = control("info").getString("simulator")
        check(marker == "3DPrintTheThings U1 simulator")
        control("reset", JSONObject())
    }

    private suspend fun withGcode(block: suspend (File) -> Unit) {
        val file = File.createTempFile("simulator-", ".gcode",
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            file.writeText("G90\nM82\nG1 X1 E1\n")
            block(file)
        } finally { file.delete() }
    }

    private fun eventCount(kind: String): Int {
        val events = control("state").getJSONArray("events")
        return (0 until events.length()).count { events.getJSONObject(it).getString("kind") == kind }
    }

    @Test fun authenticatedUploadAndStartUsesActualClientAndCoordinator() = runBlocking {
        withGcode { file ->
            var reported = 0L
            val result = uploadAndStartJob(MoonrakerTransport(client()), file, "fixture.gcode",
                { sent, _ -> reported = sent }, { true })
            assertEquals(TransportCommandResult.Success, result)
            assertEquals(file.length(), reported)
            assertEquals("printing", control("state").getString("state"))
            assertEquals(1, eventCount("upload_request"))
            assertEquals(1, eventCount("start"))
        }
    }

    @Test fun authenticationAndBusyPreflightBlockUploads() = runBlocking {
        withGcode { file ->
            for (configuration in listOf("auth", "busy")) {
                if (configuration == "busy") control("configure", JSONObject().put("state", "paused"))
                val actual = client(if (configuration == "auth") "wrong-key" else "simulator-test-key")
                val result = uploadAndStartJob(MoonrakerTransport(actual), file, "fixture.gcode", { _, _ -> }, { true })
                assertTrue(result is TransportCommandResult.Failure)
                val reason = (result as TransportCommandResult.Failure).reason
                assertTrue(reason, reason.contains(if (configuration == "auth") "authorization" else "paused", ignoreCase = true))
            }
            assertEquals(0, eventCount("upload_request"))
            assertEquals(0, eventCount("start"))
        }
    }

    @Test fun disconnectedUploadIsNotReplayedOrStarted() = runBlocking {
        control("configure", JSONObject().put("upload_mode", "disconnect"))
        withGcode { file ->
            val result = uploadAndStartJob(MoonrakerTransport(client()), file, "fixture.gcode", { _, _ -> }, { true })
            assertTrue(result is TransportCommandResult.Failure)
            assertEquals(1, eventCount("upload_request"))
            assertEquals(0, eventCount("start"))
        }
    }

    @Test fun unconfirmedUploadDoesNotStartOrReplay() = runBlocking {
        control("configure", JSONObject().put("upload_mode", "malformed"))
        withGcode { file ->
            val result = uploadAndStartJob(MoonrakerTransport(client()), file, "fixture.gcode", { _, _ -> }, { true })
            assertTrue(result is TransportCommandResult.Failure)
            assertEquals(1, eventCount("upload_accepted"))
            assertEquals(1, eventCount("upload_request"))
            assertEquals(0, eventCount("start"))
        }
    }

    @Test fun rejectedUploadReportsStorageErrorWithoutStarting() = runBlocking {
        control("configure", JSONObject().put("upload_mode", "reject"))
        withGcode { file ->
            val result = uploadAndStartJob(MoonrakerTransport(client()), file, "fixture.gcode", { _, _ -> }, { true })
            assertTrue(result is TransportCommandResult.Failure)
            val reason = (result as TransportCommandResult.Failure).reason
            assertTrue(reason, reason.contains("507") && reason.contains("storage full"))
            assertEquals(1, eventCount("upload_request"))
            assertEquals(0, eventCount("start"))
        }
    }

    @Test fun cancellationAndCoordinatorInvalidationCannotStartJob() = runBlocking {
        control("configure", JSONObject().put("upload_mode", "slow"))
        withGcode { file ->
            val job = launch(Dispatchers.IO) {
                uploadAndStartJob(MoonrakerTransport(client()), file, "fixture.gcode", { _, _ -> }, { true })
            }
            withTimeout(5_000) { while (eventCount("upload_request") == 0) delay(25) }
            withTimeout(2_000) { job.cancelAndJoin() }
            withTimeout(12_000) { while (eventCount("upload_finished") == 0) delay(25) }
            assertEquals(0, eventCount("start"))
            control("configure", JSONObject().put("upload_mode", "ok"))
            var current = true
            val result = uploadAndStartJob(MoonrakerTransport(client()), file, "switch.gcode",
                { sent, total -> if (sent == total) current = false }, { current })
            assertTrue(result is TransportCommandResult.Failure)
            assertEquals(0, eventCount("start"))
        }
    }

    @Test fun changingSpoolsAndReconnectUseFreshResponses() = runBlocking {
        val actual = client()
        assertEquals(listOf(true, true, true, false), actual.queryFilamentSlots()!!.map { it.loaded })
        control("configure", JSONObject().put("loaded", org.json.JSONArray(listOf(false, true, false, true)))
            .put("connected", false))
        assertEquals(listOf(false, true, false, true), actual.queryFilamentSlots()!!.map { it.loaded })
        assertNotNull(actual.validateStartJob())
        control("configure", JSONObject().put("connected", true))
        assertNull(actual.validateStartJob())
    }
}
