package com.u1.slicer.network

import com.u1.slicer.ui.printer.experimental.ExperimentalCameraReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MoonrakerMonitoringTest {
    @Test
    fun readsDisplayProgressAndReportedMetricsWithoutLosingZeroes() = runBlocking {
        server().use { server ->
            server.enqueue(MockResponse().setBody("""
                {"result":{"status":{
                  "webhooks":{"state":"ready"},
                  "print_stats":{"state":"printing","filename":"sample.gcode",
                    "total_duration":120.5,"print_duration":105,"remaining_time":180,
                    "info":{"current_layer":2,"total_layer":10}},
                  "virtual_sdcard":{"progress":0.78},
                  "display_status":{"progress":0.72},
                  "heater_bed":{"temperature":64.8,"target":65},
                  "extruder":{"temperature":21.5,"target":0},
                  "extruder2":{"temperature":221,"target":220},
                  "toolhead":{"extruder":"extruder2"}
                }}}
            """.trimIndent()))
            val client = server.client().apply { configureApiKey("fixture-key") }
            val snapshot = (client.readStatusSnapshot() as MoonrakerStatusResult.Received).snapshot
            assertEquals("ready", snapshot.klippyState)
            assertEquals("printing", snapshot.printState)
            assertEquals("sample.gcode", snapshot.filename)
            assertEquals(0.72f, snapshot.progress)
            assertEquals(0.78f, snapshot.asLegacyStatus().progress, 0.001f)
            assertEquals(120.5, snapshot.elapsedSeconds)
            assertEquals(180.0, snapshot.remainingSeconds)
            assertEquals(2, snapshot.currentLayer)
            assertEquals(10, snapshot.totalLayers)
            assertEquals("extruder2", snapshot.activeExtruder)
            assertEquals(65f, snapshot.bed?.target)
            assertEquals(0f, snapshot.extruders[0]?.target)
            assertEquals(221f, snapshot.extruders[2]?.temperature)
            assertEquals(setOf(0, 2), snapshot.extruders.keys)
            val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("GET", request.method)
            assertTrue(request.path.orEmpty().startsWith("/printer/objects/query?"))
            assertEquals("fixture-key", request.getHeader("X-Api-Key"))
        }
    }

    @Test
    fun omitsMissingMetricsAndDistinguishesHttpAndMalformedStatus() = runBlocking {
        server().use { server ->
            server.enqueue(MockResponse().setBody("""
                {"result":{"status":{
                  "print_stats":{"state":"paused","remaining_time":-1,"progress":0.25},
                  "virtual_sdcard":{"progress":"NaN"},
                  "extruder2":{"target":0}
                }}}
            """.trimIndent()))
            server.enqueue(MockResponse().setBody("""{"result":{"status":{}}}"""))
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(503))
            val client = server.client()
            val snapshot = (client.readStatusSnapshot() as MoonrakerStatusResult.Received).snapshot
            assertEquals(0.25f, snapshot.progress)
            assertNull(snapshot.elapsedSeconds)
            assertNull(snapshot.remainingSeconds)
            assertNull(snapshot.bed)
            assertNull(snapshot.extruders[2]?.temperature)
            assertEquals(MoonrakerStatusResult.Unavailable(MoonrakerJsonRead.Problem.MALFORMED), client.readStatusSnapshot())
            assertEquals(MoonrakerStatusResult.Unavailable(MoonrakerJsonRead.Problem.AUTHORIZATION), client.readStatusSnapshot())
            assertEquals(MoonrakerStatusResult.Unavailable(MoonrakerJsonRead.Problem.HTTP, 503), client.readStatusSnapshot())
        }
    }

    @Test
    fun leavingMonitoringCancelsInFlightStatusRead() = runBlocking {
        server().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val ended = CountDownLatch(1)
            val http = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun callFailed(call: Call, ioe: IOException) { ended.countDown() }
            }).build()
            val client = server.client(http)
            val request = launch(Dispatchers.IO) { client.readStatusSnapshot() }
            assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
            request.cancelAndJoin()
            assertTrue(ended.await(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun relativeU1SnapshotPrefersPrinterWebPort() = runBlocking {
        server().use { server ->
            server.enqueue(MockResponse().setBody("""
                {"result":{"webcams":[
                  {"enabled":false,"snapshot_url":"/unused.jpg"},
                  {"enabled":true,"snapshot_url":"/webcam/snapshot.jpg"}
                ]}}
            """.trimIndent()))
            val candidates = server.client().queryWebcamSnapshotCandidates()
            assertEquals("http://${server.url("/").host}/webcam/snapshot.jpg", candidates[0])
            assertEquals(server.url("/webcam/snapshot.jpg").toString(), candidates[1])
        }
    }

    @Test
    fun cameraRejectsBadFramesAndNeverSendsKeyToTheOtherCameraPort() = runBlocking {
        server().use { printer ->
            server().use { camera ->
                val advertised = """{"result":{"webcams":[{"snapshot_url":"${camera.url("/snapshot.jpg")}"}]}}"""
                printer.enqueue(MockResponse().setBody(advertised))
                camera.enqueue(MockResponse().setBody("<html>not a snapshot</html>"))
                printer.enqueue(MockResponse().setResponseCode(404))
                printer.enqueue(MockResponse().setBody(advertised))
                camera.enqueue(MockResponse().setBody("fixture-frame"))
                camera.enqueue(MockResponse().setBody("fixture-frame"))
                val reader = ExperimentalCameraReader(printer.client().apply { configureApiKey("fixture-key") })
                fun decode(bytes: ByteArray): String? = bytes.decodeToString().takeIf { it == "fixture-frame" }
                assertNull(reader.nextFrame(::decode))
                val expected = ExperimentalCameraReader.Frame("fixture-frame", isStoredMonitorFile = false)
                assertEquals(expected, reader.nextFrame(::decode))
                assertEquals(expected, reader.nextFrame(::decode))
                repeat(3) {
                    assertNull(requireNotNull(camera.takeRequest(2, TimeUnit.SECONDS)).getHeader("X-Api-Key"))
                    assertEquals("fixture-key", requireNotNull(printer.takeRequest(2, TimeUnit.SECONDS)).getHeader("X-Api-Key"))
                }
                assertEquals(3, printer.requestCount)
            }
        }
    }

    @Test
    fun monitorFileFallbackIsIdentifiedSeparatelyFromAWebcamSnapshot() = runBlocking {
        server().use { server ->
            val unavailable = server.url("/snapshot.jpg")
            server.enqueue(MockResponse().setBody(
                """{"result":{"webcams":[{"snapshot_url":"$unavailable"}]}}""",
            ))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("stored-frame"))
            val result = ExperimentalCameraReader(server.client()).nextFrame { it.decodeToString() }
            assertEquals(ExperimentalCameraReader.Frame("stored-frame", isStoredMonitorFile = true), result)
        }
    }

    @Test
    fun workingWebcamReplacesStoredFallbackAfterDiscoveryCooldown() = runBlocking {
        server().use { server ->
            val webcam = server.url("/snapshot.jpg")
            val list = """{"result":{"webcams":[{"snapshot_url":"$webcam"}]}}"""
            server.enqueue(MockResponse().setBody(list))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("stored"))
            server.enqueue(MockResponse().setBody("stored"))
            server.enqueue(MockResponse().setBody(list))
            server.enqueue(MockResponse().setBody("webcam"))
            server.enqueue(MockResponse().setBody("webcam"))
            var elapsed = 0L
            val reader = ExperimentalCameraReader(server.client()) { elapsed }
            val storedFrame = ExperimentalCameraReader.Frame("stored", isStoredMonitorFile = true)
            val webcamFrame = ExperimentalCameraReader.Frame("webcam", isStoredMonitorFile = false)

            assertEquals(storedFrame, reader.nextFrame { it.decodeToString() })
            elapsed = 5_000L
            assertEquals(storedFrame, reader.nextFrame { it.decodeToString() })
            elapsed = 15_000L
            assertEquals(webcamFrame, reader.nextFrame { it.decodeToString() })
            elapsed = 30_000L
            assertEquals(webcamFrame, reader.nextFrame { it.decodeToString() })

            assertEquals(
                listOf("/server/webcams/list", "/snapshot.jpg", "/server/files/camera/monitor.jpg",
                    "/server/files/camera/monitor.jpg", "/server/webcams/list", "/snapshot.jpg", "/snapshot.jpg"),
                List(7) { requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)).path },
            )
        }
    }

    private fun server() = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }

    private fun MockWebServer.client(http: OkHttpClient = OkHttpClient()) =
        MoonrakerClient(http).apply { baseUrl = this@client.url("/").toString() }
}
