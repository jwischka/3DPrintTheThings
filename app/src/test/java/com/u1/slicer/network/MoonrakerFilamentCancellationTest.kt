package com.u1.slicer.network

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MoonrakerFilamentCancellationTest {
    @Test fun cancellingStalledSpoolQueryCompletesPromptly() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = MoonrakerClient().apply { baseUrl = server.url("/").toString() }
            val query = launch(Dispatchers.IO) { client.queryFilamentSlots() }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            withTimeout(2_000) { query.cancelAndJoin() }
        }
    }

    @Test fun cancellingWhileResponseBodyStallsCompletesPromptly() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(MockResponse().setBody("{\"result\":{\"status\":{}}}").throttleBody(1, 1, TimeUnit.SECONDS))
            val bodyStarted = CountDownLatch(1)
            val http = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun responseBodyStart(call: Call) { bodyStarted.countDown() }
            }).build()
            val client = MoonrakerClient(http).apply { baseUrl = server.url("/").toString() }
            val query = launch(Dispatchers.IO) { client.queryFilamentSlots() }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertTrue(bodyStarted.await(5, TimeUnit.SECONDS))
            withTimeout(2_000) { query.cancelAndJoin() }
        }
    }

    @Test fun oversizedSpoolResponseIsRejected() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(MockResponse().setBody("x".repeat(1024 * 1024 + 1)))
            server.enqueue(MockResponse().setResponseCode(404))
            val client = MoonrakerClient().apply { baseUrl = server.url("/").toString() }
            assertNull(client.queryFilamentSlots())
        }
    }
}
