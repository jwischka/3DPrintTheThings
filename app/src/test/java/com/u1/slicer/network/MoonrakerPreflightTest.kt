package com.u1.slicer.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MoonrakerPreflightTest {
    private val readyInfo = """{"result":{"klippy_connected":true,"klippy_state":"ready"}}"""

    private fun server(): MockWebServer = MockWebServer().also {
        it.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    private fun status(state: String, webhookState: String = "ready") =
        """{"result":{"status":{"webhooks":{"state":"$webhookState"},"print_stats":{"state":"$state"}}}}"""

    @Test
    fun `only terminal print states permit start`() = runBlocking {
        val server = server()
        try {
            val client = MoonrakerClient(OkHttpClient()).apply { baseUrl = server.url("/proxy").toString() }
            val expected = mapOf(
                "standby" to null,
                "complete" to null,
                "error" to null,
                "printing" to "Printer is already printing",
                "paused" to "A print is paused",
                "unknown" to "Print state is unknown",
            )
            for ((state, outcome) in expected) {
                server.enqueue(MockResponse().setBody(readyInfo))
                server.enqueue(MockResponse().setBody(status(state)))
                assertEquals("state=$state", outcome, client.validateStartJob())
                assertEquals("/proxy/server/info", server.takeRequest().path)
                assertEquals("/proxy/printer/objects/query?webhooks&print_stats", server.takeRequest().path)
            }
        } finally { server.shutdown() }
    }

    @Test
    fun `unavailable Klippy and invalid server replies block start`() = runBlocking {
        val server = server()
        try {
            val client = MoonrakerClient(OkHttpClient()).apply { baseUrl = server.url("/").toString() }
            val cases = listOf(
                """{"result":{"klippy_connected":false,"klippy_state":"ready"}}""" to "Klippy is disconnected",
                """{"result":{"klippy_connected":true,"klippy_state":"startup"}}""" to "Klippy is starting up",
                """{"result":{"klippy_connected":true,"klippy_state":"error"}}""" to "Klippy has an error",
                """{"result":{"klippy_connected":true,"klippy_state":"shutdown"}}""" to "Klippy is shut down",
                """{"result":{"klippy_connected":true,"klippy_state":"other"}}""" to "Klippy readiness is unknown",
                "<html>proxy error</html>" to "Moonraker response is malformed",
            )
            for ((body, outcome) in cases) {
                server.enqueue(MockResponse().setBody(body))
                assertEquals(outcome, client.validateStartJob())
                assertEquals("/server/info", server.takeRequest().path)
            }
            server.enqueue(MockResponse().setResponseCode(401))
            assertEquals("Moonraker authorization required", client.validateStartJob())
            assertEquals("/server/info", server.takeRequest().path)
        } finally { server.shutdown() }
    }

    @Test
    fun `missing status objects and unready webhooks block start`() = runBlocking {
        val server = server()
        try {
            val client = MoonrakerClient(OkHttpClient()).apply { baseUrl = server.url("/").toString() }
            val cases = listOf(
                """{"result":{"status":{}}}""" to "Printer readiness is missing",
                """{"result":{"status":{"webhooks":{"state":"ready"}}}}""" to "Print status is missing",
                status("standby", "shutdown") to "Printer is not ready",
            )
            for ((body, outcome) in cases) {
                server.enqueue(MockResponse().setBody(readyInfo))
                server.enqueue(MockResponse().setBody(body))
                assertEquals(outcome, client.validateStartJob())
                assertEquals("/server/info", server.takeRequest().path)
                assertEquals("/printer/objects/query?webhooks&print_stats", server.takeRequest().path)
            }
        } finally { server.shutdown() }
    }

    @Test
    fun `cancellation closes underlying HTTP call`() = runBlocking {
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
            val job = launch(Dispatchers.IO) { client.validateStartJob() }
            assertEquals("/server/info", server.takeRequest(5, TimeUnit.SECONDS)?.path)
            withTimeout(5_000) { job.cancelAndJoin() }
            assertTrue(callEnded.await(5, TimeUnit.SECONDS))
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
}
