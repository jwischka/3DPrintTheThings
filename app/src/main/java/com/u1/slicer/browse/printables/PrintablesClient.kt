package com.u1.slicer.browse.printables

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class PrintablesPrint(
    val id: String,
    val name: String,
    val slug: String?,
    val imageUrl: String?,
    val author: String?,
) {
    val pageUrl: String get() = "https://www.printables.com/model/$id" +
        (slug?.takeIf { it.isNotBlank() }?.let { "-$it" } ?: "")
}

enum class PrintablesFileType(val apiValue: String) {
    STL("stl"), GCODE("gcode"), OTHER("other")
}

data class PrintablesFile(
    val id: String,
    val name: String,
    val sizeBytes: Long?,
    val type: PrintablesFileType,
) {
    // Printables puts 3MF model files in the `stls` GraphQL field.
    val isSlicable: Boolean get() = name.lowercase(Locale.ROOT).let {
        it.endsWith(".3mf") || it.endsWith(".stl") || it.endsWith(".obj") || it.endsWith(".step")
    }
}

data class PrintablesPrintDetail(val print: PrintablesPrint, val files: List<PrintablesFile>)

data class PrintablesDownloadLink(val url: String, val ttlSeconds: Long?)

sealed class PrintablesApiException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class Http(val status: Int) : PrintablesApiException("Printables HTTP $status")
    class GraphQl(val messages: List<String>) : PrintablesApiException(messages.joinToString("; "))
    class InvalidResponse(message: String, cause: Throwable? = null) : PrintablesApiException(message, cause)
}

/** Unofficial anonymous Printables GraphQL API. Call on a coroutine, never on the UI thread. */
class PrintablesClient(
    private val httpClient: OkHttpClient,
    private val endpoint: String = "https://api.printables.com/graphql/",
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val waitMillis: suspend (Long) -> Unit = { delay(it) },
) {
    private data class SearchKey(val query: String, val limit: Int, val ordering: SearchOrdering)
    private data class CachedSearch(val timestamp: Long, val prints: List<PrintablesPrint>)
    private val searchCache = LinkedHashMap<SearchKey, CachedSearch>()
    private val cacheLock = Any()

    enum class SearchOrdering(val apiValue: String) {
        BEST_MATCH("best_match"), POPULAR("popular"), LATEST("latest"), RATING("rating"), MAKES_COUNT("makes_count")
    }

    suspend fun search(
        query: String,
        limit: Int = 24,
        ordering: SearchOrdering = SearchOrdering.BEST_MATCH,
    ): List<PrintablesPrint> {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val key = SearchKey(trimmed, limit, ordering)
        synchronized(cacheLock) {
            searchCache[key]?.takeIf { clockMillis() - it.timestamp in 0L until CACHE_MILLIS }?.let {
                return it.prints
            }
        }
        val data = request(SEARCH_QUERY, JSONObject().put("query", trimmed).put("limit", limit)
            .put("ordering", ordering.apiValue))
        val items = data.requiredObject("searchPrints2").requiredArray("items")
        val prints = parseResponse { (0 until items.length()).map { parsePrint(items.getJSONObject(it)) } }
        synchronized(cacheLock) {
            val now = clockMillis()
            searchCache.entries.removeAll { now - it.value.timestamp !in 0L until CACHE_MILLIS }
            searchCache.remove(key)
            searchCache[key] = CachedSearch(now, prints)
            if (searchCache.size > MAX_CACHE_ENTRIES) searchCache.remove(searchCache.keys.first())
        }
        return prints
    }

    suspend fun getPrint(id: String): PrintablesPrintDetail {
        require(id.isNotBlank()) { "print id is required" }
        val data = request(FILES_QUERY, JSONObject().put("id", id))
        val model = data.requiredObject("print")
        return parseResponse {
            val print = parsePrint(model)
            val files = buildList {
                addAll(parseFiles(model.requiredArray("stls"), PrintablesFileType.STL))
                addAll(parseFiles(model.requiredArray("gcodes"), PrintablesFileType.GCODE))
                addAll(parseFiles(model.requiredArray("otherFiles"), PrintablesFileType.OTHER))
            }
            PrintablesPrintDetail(print, files)
        }
    }

    suspend fun getDownloadLink(printId: String, file: PrintablesFile): PrintablesDownloadLink =
        getDownloadLink(printId, file.id, file.type)

    /** Resolve immediately before downloading; signed links expire and are never cached. */
    suspend fun getDownloadLink(printId: String, fileId: String, type: PrintablesFileType): PrintablesDownloadLink {
        require(printId.isNotBlank() && fileId.isNotBlank()) { "print and file ids are required" }
        val data = request(DOWNLOAD_QUERY, JSONObject().put("id", fileId).put("printId", printId)
            .put("fileType", type.apiValue).put("source", "model_detail"))
        val result = data.requiredObject("getDownloadLink")
        if (!result.optBoolean("ok")) {
            val messages = mutableListOf<String>()
            result.optJSONArray("errors")?.let { errors ->
                for (i in 0 until errors.length()) {
                    val entry = errors.optJSONObject(i) ?: continue
                    val values = entry.optJSONArray("messages")
                    if (values != null) for (j in 0 until values.length()) messages += values.optString(j)
                }
            }
            throw PrintablesApiException.GraphQl(messages.ifEmpty { listOf("Printables did not provide a download link") })
        }
        val output = result.requiredObject("output")
        val url = output.requiredString("link")
        val parsedUrl = url.toHttpUrlOrNull()
        if (parsedUrl == null || !parsedUrl.isHttps || parsedUrl.username.isNotEmpty() || parsedUrl.password.isNotEmpty())
            throw PrintablesApiException.InvalidResponse("Printables returned an insecure download link")
        val ttl = output.optLong("ttl", -1).takeIf { it >= 0 }
        return PrintablesDownloadLink(url, ttl)
    }

    private suspend fun request(query: String, variables: JSONObject): JSONObject {
        val payload = JSONObject().put("query", query).put("variables", variables).toString()
        var lastFailure: IOException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            val response = try {
                execute(payload)
            } catch (e: IOException) {
                if (e is PrintablesApiException) throw e
                lastFailure = e
                if (attempt < MAX_ATTEMPTS - 1) waitMillis(backoff(attempt))
                return@repeat
            }
            if (response.status !in 200..299) {
                val error = PrintablesApiException.Http(response.status)
                if (response.status in RETRYABLE_STATUSES && attempt < MAX_ATTEMPTS - 1) {
                    lastFailure = error
                    waitMillis(backoff(attempt))
                    return@repeat
                }
                throw error
            }
            val root = try { JSONObject(response.body) } catch (e: JSONException) {
                throw PrintablesApiException.InvalidResponse("Printables returned invalid JSON", e)
            }
            root.optJSONArray("errors")?.takeIf { it.length() > 0 }?.let { errors ->
                throw PrintablesApiException.GraphQl((0 until errors.length()).map { i ->
                    errors.optJSONObject(i)?.optString("message")?.takeIf { it.isNotBlank() }
                        ?: "Unknown GraphQL error"
                })
            }
            return root.requiredObject("data")
        }
        throw lastFailure ?: PrintablesApiException.InvalidResponse("Printables request failed")
    }

    private data class HttpResult(val status: Int, val body: String)

    private suspend fun execute(payload: String): HttpResult = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(endpoint)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE)).build()
        val call = httpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        val result = HttpResult(it.code, readBody(it))
                        if (continuation.isActive) continuation.resume(result)
                    }
                } catch (e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }

    private fun backoff(attempt: Int) = 250L shl attempt

    private fun readBody(response: Response): String {
        val body = response.body ?: return ""
        if (body.contentLength() > MAX_BODY_BYTES) {
            throw PrintablesApiException.InvalidResponse("Printables response is too large")
        }
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        body.byteStream().use { stream ->
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (bytes.size() + count > MAX_BODY_BYTES) {
                    throw PrintablesApiException.InvalidResponse("Printables response is too large")
                }
                bytes.write(buffer, 0, count)
            }
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    private inline fun <T> parseResponse(block: () -> T): T = try {
        block()
    } catch (e: JSONException) {
        throw PrintablesApiException.InvalidResponse("Printables response has invalid fields", e)
    }

    private fun parsePrint(json: JSONObject): PrintablesPrint {
        val imagePath = json.optJSONObject("image")?.optionalString("filePath")
        return PrintablesPrint(
            id = json.requiredString("id"),
            name = json.requiredString("name"),
            slug = json.optionalString("slug"),
            imageUrl = imagePath?.let { "https://media.printables.com/" + it.trimStart('/') },
            author = json.optJSONObject("user")?.optionalString("publicUsername"),
        )
    }

    private fun parseFiles(items: JSONArray, type: PrintablesFileType): List<PrintablesFile> =
        (0 until items.length()).map { index ->
            val json = items.getJSONObject(index)
            PrintablesFile(json.requiredString("id"), json.requiredString("name"),
                json.optLong("fileSize", -1).takeIf { it >= 0 }, type)
        }

    private fun JSONObject.requiredObject(name: String): JSONObject = optJSONObject(name)
        ?: throw PrintablesApiException.InvalidResponse("Printables response missing $name")

    private fun JSONObject.requiredArray(name: String): JSONArray = optJSONArray(name)
        ?: throw PrintablesApiException.InvalidResponse("Printables response missing $name")

    private fun JSONObject.requiredString(name: String): String = (opt(name) as? String)?.takeIf { it.isNotBlank() }
        ?: throw PrintablesApiException.InvalidResponse("Printables response missing $name")

    private fun JSONObject.optionalString(name: String): String? = (opt(name) as? String)?.takeIf { it.isNotBlank() }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val CACHE_MILLIS = 5 * 60 * 1000L
        const val MAX_CACHE_ENTRIES = 32
        const val MAX_BODY_BYTES = 2 * 1024 * 1024
        const val MAX_ATTEMPTS = 3
        val RETRYABLE_STATUSES = setOf(429, 500, 502, 503, 504)

        val SEARCH_QUERY = """query SearchPrints(${'$'}query: String!, ${'$'}limit: Int!, ${'$'}ordering: SearchChoicesEnum) {
            searchPrints2(query: ${'$'}query, printType: print, limit: ${'$'}limit, ordering: ${'$'}ordering) {
                items { id name slug image { filePath } user { publicUsername } }
            }
        }"""
        val FILES_QUERY = """query PrintFiles(${'$'}id: ID!) {
            print(id: ${'$'}id) { id name slug image { filePath } user { publicUsername }
                stls { id name fileSize } gcodes { id name fileSize } otherFiles { id name fileSize } }
        }"""
        val DOWNLOAD_QUERY = """mutation DownloadLink(${'$'}id: ID!, ${'$'}printId: ID!, ${'$'}fileType: DownloadFileTypeEnum!, ${'$'}source: DownloadSourceEnum!) {
            getDownloadLink(id: ${'$'}id, printId: ${'$'}printId, fileType: ${'$'}fileType, source: ${'$'}source) {
                ok errors { messages } output { link ttl }
            }
        }"""
    }
}
