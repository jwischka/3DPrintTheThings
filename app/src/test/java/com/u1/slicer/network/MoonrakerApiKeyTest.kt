package com.u1.slicer.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MoonrakerApiKeyTest {
    private fun server() = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }

    @Test
    fun `key is sent to configured origin and path prefix`() = runBlocking {
        val server = server()
        try {
            server.enqueue(MockResponse().setBody("{}"))
            val client = MoonrakerClient(OkHttpClient()).apply { baseUrl = server.url("/proxy").toString() }
            client.configureApiKey("private-key")
            assertNull(client.testConnection())
            val request = server.takeRequest()
            assertEquals("/proxy/server/info", request.path)
            assertEquals("private-key", request.getHeader("X-Api-Key"))
        } finally { server.shutdown() }
    }

    @Test
    fun `redirect to another port does not carry API key`() = runBlocking {
        val source = server()
        val destination = server()
        try {
            source.enqueue(MockResponse().setResponseCode(302)
                .addHeader("Location", destination.url("/server/info")))
            destination.enqueue(MockResponse().setBody("{}"))
            val client = MoonrakerClient(OkHttpClient()).apply { baseUrl = source.url("/proxy").toString() }
            client.configureApiKey("private-key")
            assertNull(client.testConnection())
            assertEquals("private-key", source.takeRequest().getHeader("X-Api-Key"))
            assertNull(destination.takeRequest().getHeader("X-Api-Key"))
        } finally {
            source.shutdown()
            destination.shutdown()
        }
    }

    @Test
    fun `redirect outside configured prefix strips API key on same origin`() = runBlocking {
        val server = server()
        try {
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/outside"))
            server.enqueue(MockResponse().setBody("{}"))
            val client = MoonrakerClient(OkHttpClient()).apply { baseUrl = server.url("/proxy").toString() }
            client.configureApiKey("private-key")
            assertNull(client.testConnection())
            assertEquals("private-key", server.takeRequest().getHeader("X-Api-Key"))
            val redirected = server.takeRequest()
            assertEquals("/outside", redirected.path)
            assertNull(redirected.getHeader("X-Api-Key"))
        } finally { server.shutdown() }
    }

    @Test
    fun `changing URL clears key and explicit clear removes key`() = runBlocking {
        val first = server()
        val second = server()
        try {
            first.enqueue(MockResponse().setBody("{}"))
            second.enqueue(MockResponse().setBody("{}"))
            second.enqueue(MockResponse().setBody("{}"))
            val client = MoonrakerClient(OkHttpClient()).apply { baseUrl = first.url("/").toString() }
            client.configureApiKey("private-key")
            assertNull(client.testConnection())
            assertEquals("private-key", first.takeRequest().getHeader("X-Api-Key"))
            client.baseUrl = second.url("/").toString()
            assertNull(client.testConnection())
            assertNull(second.takeRequest().getHeader("X-Api-Key"))
            client.configureApiKey("second-key")
            client.configureApiKey(null)
            assertNull(client.testConnection())
            assertNull(second.takeRequest().getHeader("X-Api-Key"))
        } finally {
            first.shutdown()
            second.shutdown()
        }
    }

    @Test
    fun `preflight and upload use scoped key`() = runBlocking {
        val server = server()
        val file = File.createTempFile("moonraker-key", ".gcode")
        try {
            file.writeText("G1 X1")
            server.enqueue(MockResponse().setBody("""{"result":{"klippy_connected":true,"klippy_state":"ready"}}"""))
            server.enqueue(MockResponse().setBody("""{"result":{"status":{"webhooks":{"state":"ready"},"print_stats":{"state":"standby"}}}}"""))
            server.enqueue(MockResponse().setBody("""{"result":{"item":{"path":"cube.gcode"}}}"""))
            val client = MoonrakerClient(OkHttpClient()).apply { baseUrl = server.url("/proxy").toString() }
            client.configureApiKey("private-key")
            assertNull(client.validateStartJob())
            assertEquals(MoonrakerUploadResult.Success, client.uploadGcodeResult(file, "cube.gcode"))
            repeat(3) { assertEquals("private-key", server.takeRequest().getHeader("X-Api-Key")) }
        } finally {
            file.delete()
            server.shutdown()
        }
    }

    @Test
    fun `camera wake WebSocket sends scoped key explicitly`() = runBlocking {
        val server = server()
        try {
            val closed = CountDownLatch(1)
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    closed.countDown()
                }
            }))
            val client = MoonrakerClient(OkHttpClient()).apply { baseUrl = server.url("/proxy").toString() }
            client.configureApiKey("private-key")
            client.wakeCamera()
            val request = server.takeRequest()
            assertEquals("/proxy/websocket", request.path)
            assertEquals("private-key", request.getHeader("X-Api-Key"))
            assertTrue(closed.await(5, TimeUnit.SECONDS))
        } finally { server.shutdown() }
    }

    @Test
    fun `snapshot uses key only for scoped Moonraker URL`() = runBlocking {
        val moonraker = server()
        val externalCamera = server()
        try {
            moonraker.enqueue(MockResponse().setBody("jpeg-a"))
            externalCamera.enqueue(MockResponse().setBody("jpeg-b"))
            val client = MoonrakerClient(OkHttpClient()).apply { baseUrl = moonraker.url("/proxy").toString() }
            client.configureApiKey("private-key")
            assertEquals("jpeg-a", client.fetchSnapshot(moonraker.url("/proxy/camera.jpg").toString())?.decodeToString())
            assertEquals("private-key", moonraker.takeRequest().getHeader("X-Api-Key"))
            assertEquals("jpeg-b", client.fetchSnapshot(externalCamera.url("/snapshot.jpg").toString())?.decodeToString())
            assertNull(externalCamera.takeRequest().getHeader("X-Api-Key"))
        } finally {
            moonraker.shutdown()
            externalCamera.shutdown()
        }
    }

    @Test
    fun `snapshot rejects oversize and does not follow redirect`() = runBlocking {
        val server = server()
        try {
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(8_388_609))))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/other"))
            val client = MoonrakerClient(OkHttpClient()).apply { baseUrl = server.url("/").toString() }
            assertNull(client.fetchSnapshot(server.url("/oversize.jpg").toString()))
            assertNull(client.fetchSnapshot(server.url("/redirect.jpg").toString()))
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test
    fun `snapshot cancellation ends underlying call`() = runBlocking {
        val server = server()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val callEnded = CountDownLatch(1)
            val http = OkHttpClient.Builder().eventListenerFactory {
                object : EventListener() {
                    override fun callFailed(call: Call, ioe: IOException) { callEnded.countDown() }
                }
            }.build()
            val client = MoonrakerClient(http).apply { baseUrl = server.url("/").toString() }
            val job = launch(Dispatchers.IO) { client.fetchSnapshot(server.url("/camera.jpg").toString()) }
            assertEquals("/camera.jpg", server.takeRequest(5, TimeUnit.SECONDS)?.path)
            withTimeout(5_000) { job.cancelAndJoin() }
            assertTrue(callEnded.await(5, TimeUnit.SECONDS))
        } finally { server.shutdown() }
    }

    @Test
    fun `invalid API keys are rejected without their contents in messages`() {
        val client = MoonrakerClient(OkHttpClient()).apply { baseUrl = "http://printer.local" }
        client.configureApiKey("x".repeat(1024))
        client.configureApiKey(" x y ")
        for (key in listOf("", "   ", "bad\r\nX-Other: leaked", "x".repeat(1025))) {
            val failure = runCatching { client.configureApiKey(key) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals("Invalid Moonraker API key", failure?.message)
        }
    }

    @Test
    fun `upload error redacts key bound when request began`() = runBlocking {
        val server = server()
        val file = File.createTempFile("moonraker-redaction", ".gcode")
        val received = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            file.writeText("G1 X1")
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    received.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    return MockResponse().setResponseCode(403)
                        .setBody("""{"error":{"message":"bad key private-key"}}""")
                }
            }
            val client = MoonrakerClient(OkHttpClient()).apply { baseUrl = server.url("/").toString() }
            client.configureApiKey("private-key")
            val result = async(Dispatchers.IO) { client.uploadGcodeResult(file, "cube.gcode") }
            assertTrue(received.await(5, TimeUnit.SECONDS))
            client.configureApiKey("new-key")
            release.countDown()
            assertEquals(MoonrakerUploadResult.Failure("Upload failed (HTTP 403): bad key [redacted]"), result.await())
            assertEquals("private-key", server.takeRequest().getHeader("X-Api-Key"))
        } finally {
            release.countDown()
            file.delete()
            server.shutdown()
        }
    }
}
