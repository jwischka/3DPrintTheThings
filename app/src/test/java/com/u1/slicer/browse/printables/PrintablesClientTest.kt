package com.u1.slicer.browse.printables

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Small synthetic payloads matching the anonymous GraphQL shapes verified on 2026-11-13. */
class PrintablesClientTest {
    private lateinit var server: MockWebServer
    private var now = 0L
    private val delays = mutableListOf<Long>()
    private lateinit var client: PrintablesClient

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = PrintablesClient(OkHttpClient(), server.url("/graphql/").toString(),
            clockMillis = { now }, waitMillis = { delays += it })
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun searchUsesVariablesAndCachesForFiveMinutes() = runBlocking {
        server.enqueue(json("""{"data":{"searchPrints2":{"items":[{"id":"3161","name":"Bench y","slug":"bench-y","image":{"filePath":"media/cover.jpg"},"user":{"publicUsername":"Prusa"}}]}}}"""))
        val results = client.search("  benchy \" query  ")
        assertEquals("3161", results.single().id)
        assertEquals("https://www.printables.com/model/3161-bench-y", results.single().pageUrl)
        assertEquals("https://media.printables.com/media/cover.jpg", results.single().imageUrl)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertTrue(body.getString("query").contains("${'$'}query"))
        assertFalse(body.getString("query").contains("benchy \" query"))
        assertEquals("benchy \" query", body.getJSONObject("variables").getString("query"))
        assertEquals("best_match", body.getJSONObject("variables").getString("ordering"))

        now = 299_999
        assertEquals(results, client.search("benchy \" query"))
        assertEquals(0, server.requestCount - 1)
        server.enqueue(json("""{"data":{"searchPrints2":{"items":[]}}}"""))
        now = 300_000
        assertTrue(client.search("benchy \" query").isEmpty())
        assertEquals(2, server.requestCount)
    }

    @Test fun detailIncludesThreeMfFromStlsAndResolvesSignedLink() = runBlocking {
        server.enqueue(json("""{"data":{"print":{"id":"3161","name":"Bench y","slug":"bench-y","stls":[{"id":"42","name":"model.3mf","fileSize":8192},{"id":"43","name":"model.stl","fileSize":2048}],"gcodes":[],"otherFiles":[{"id":"44","name":"notes.txt","fileSize":9}]}}}"""))
        val detail = client.getPrint("3161")
        assertEquals(3, detail.files.size)
        assertEquals(PrintablesFileType.STL, detail.files[0].type)
        assertTrue(detail.files[0].isSlicable)
        assertFalse(detail.files[2].isSlicable)
        assertEquals(8192L, detail.files[0].sizeBytes)
        assertEquals("3161", JSONObject(server.takeRequest().body.readUtf8()).getJSONObject("variables").getString("id"))

        server.enqueue(json("""{"data":{"getDownloadLink":{"ok":true,"output":{"link":"https://files.printables.com/signed/model.3mf?token=example","ttl":86400}}}}"""))
        val link = client.getDownloadLink(detail.print.id, detail.files[0])
        assertEquals(86400L, link.ttlSeconds)
        assertTrue(link.url.endsWith("token=example"))
        val variables = JSONObject(server.takeRequest().body.readUtf8()).getJSONObject("variables")
        assertEquals("stl", variables.getString("fileType"))
        assertEquals("model_detail", variables.getString("source"))
        assertEquals("42", variables.getString("id"))
    }

    @Test fun graphqlErrorIsReturnedWithoutRetry() {
        server.enqueue(json("""{"errors":[{"message":"Unknown field"}],"data":null}"""))
        val error = assertThrows(PrintablesApiException.GraphQl::class.java) {
            runBlocking { client.search("benchy") }
        }
        assertEquals(listOf("Unknown field"), error.messages)
        assertEquals(1, server.requestCount)
    }

    @Test fun transientHttpErrorsHaveBoundedBackoff() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(json("""{"data":{"searchPrints2":{"items":[]}}}"""))
        assertTrue(client.search("benchy").isEmpty())
        assertEquals(listOf(250L, 500L), delays)
        assertEquals(3, server.requestCount)
    }

    @Test fun searchCacheKeysIncludeLimitAndEvictOldestEntry() = runBlocking {
        repeat(34) { server.enqueue(json("""{"data":{"searchPrints2":{"items":[]}}}""")) }
        client.search("same", limit = 1)
        client.search("same", limit = 2)
        assertEquals(2, server.requestCount)
        repeat(31) { client.search("q$it") }
        assertEquals(33, server.requestCount)
        client.search("same", limit = 1)
        assertEquals(34, server.requestCount)
    }

    @Test fun retryStopsAfterThreeAttempts() {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }
        val error = assertThrows(PrintablesApiException.Http::class.java) {
            runBlocking { client.search("unavailable") }
        }
        assertEquals(503, error.status)
        assertEquals(listOf(250L, 500L), delays)
        assertEquals(3, server.requestCount)
    }

    @Test fun malformedItemsAndNullRequiredFieldsFailAsInvalidResponse() {
        server.enqueue(json("""{"data":{"searchPrints2":{"items":[null]}}}"""))
        assertThrows(PrintablesApiException.InvalidResponse::class.java) {
            runBlocking { client.search("bad") }
        }
        server.enqueue(json("""{"data":{"searchPrints2":{"items":[{"id":null,"name":"bad"}]}}}"""))
        assertThrows(PrintablesApiException.InvalidResponse::class.java) {
            runBlocking { client.search("null") }
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun oversizedBodyFailsWithoutRetry() {
        server.enqueue(json("x".repeat(2 * 1024 * 1024 + 1)))
        assertThrows(PrintablesApiException.InvalidResponse::class.java) {
            runBlocking { client.search("large") }
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun cancellationCancelsCallWithoutRetry() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch(start = CoroutineStart.UNDISPATCHED) { client.search("wait") }
        assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
        job.cancelAndJoin()
        assertEquals(1, server.requestCount)
        assertTrue(delays.isEmpty())
    }

    @Test fun permanentHttpAndInvalidDownloadLinkFailCleanly() {
        server.enqueue(MockResponse().setResponseCode(403))
        val error = assertThrows(PrintablesApiException.Http::class.java) {
            runBlocking { client.search("benchy") }
        }
        assertEquals(403, error.status)
        assertEquals(1, server.requestCount)

        server.enqueue(json("""{"data":{"getDownloadLink":{"ok":false,"errors":[{"messages":["File unavailable"]}]}}}"""))
        val linkError = assertThrows(PrintablesApiException.GraphQl::class.java) {
            runBlocking { client.getDownloadLink("3161", "42", PrintablesFileType.STL) }
        }
        assertEquals(listOf("File unavailable"), linkError.messages)
    }

    private fun json(body: String) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/json").setBody(body)
}
