package com.u1.slicer.browse

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
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PrintablesThumbnailRequestsTest {
    @Test fun requestsOnlyAnonymousImagesOnTheOfficialHttpsOrigin() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val thumbnails = PrintablesThumbnailRequests(localClient(server))
            for (url in listOf(
                "http://media.printables.com/image.png",
                "https://media.printables.com.example.org/image.png",
                "https://user:password@media.printables.com/image.png",
                "https://media.printables.com:8443/image.png",
            )) {
                assertNull(thumbnails.fetch(url))
            }
            assertEquals(0, server.requestCount)

            val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(pngBytes)))
            assertArrayEquals(pngBytes, thumbnails.fetch(IMAGE_URL))
            val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("GET", request.method)
            assertEquals("/image.png", request.path)
            assertNull(request.getHeader("Cookie"))
            assertNull(request.getHeader("Authorization"))
        }
    }

    @Test fun rejectsRedirectsNonImagesAndOversizedChunkedBodies() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val thumbnails = PrintablesThumbnailRequests(localClient(server))
            server.enqueue(MockResponse().setResponseCode(302)
                .setHeader("Location", "https://media.printables.com/redirect.png"))
            assertNull(thumbnails.fetch(IMAGE_URL))
            assertEquals(1, server.requestCount)

            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>Challenge</html>"))
            assertNull(thumbnails.fetch(IMAGE_URL))

            val oversized = Buffer().write(ByteArray(2 * 1024 * 1024 + 1))
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setChunkedBody(oversized, 16384))
            assertNull(thumbnails.fetch(IMAGE_URL))
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun cancellingTheRowCancelsItsHttpCall() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val cancelled = CountDownLatch(1)
            val client = localClient(server).newBuilder().eventListener(object : EventListener() {
                override fun canceled(call: Call) {
                    cancelled.countDown()
                }
            }).build()
            val job = launch(Dispatchers.Default) { PrintablesThumbnailRequests(client).fetch(IMAGE_URL) }
            try {
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            } finally {
                job.cancelAndJoin()
            }
            assertTrue(cancelled.await(5, TimeUnit.SECONDS))
        }
    }

    private fun localClient(server: MockWebServer): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath)).build())
    }.build()

    private companion object {
        const val IMAGE_URL = "https://media.printables.com/image.png"
    }
}
