package com.u1.slicer.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class DownloadProvenance(
    val site: String,
    val pageUrl: String,
    val title: String,
    val author: String? = null,
    val license: String? = null,
    val thumbnailUrl: String? = null,
)

data class DownloadEntry(
    val sha256: String,
    val fileName: String,
    val sizeBytes: Long,
    val createdAtMillis: Long,
    val provenance: DownloadProvenance,
)

/** Stores model bytes under filesDir/downloads/<sha256>/ with a small atomic JSON index per file. */
class DownloadStore(
    filesDir: File,
    httpClient: OkHttpClient,
    private val maxBytes: Long = 256L * 1024 * 1024,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val root = File(filesDir, "downloads")
    private val client = httpClient.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()
    private val mutex = Mutex()

    init { require(maxBytes > 0) }

    suspend fun download(
        url: String,
        filename: String,
        provenance: DownloadProvenance,
        headers: Map<String, String> = emptyMap(),
    ): DownloadEntry = withContext(Dispatchers.IO) {
        val safeName = safeFileName(filename)
        val safeProvenance = provenance.copy(
            pageUrl = publicUrl(provenance.pageUrl),
            thumbnailUrl = provenance.thumbnailUrl?.let(::publicUrl),
        )
        val parsedUrl = url.toHttpUrlOrNull() ?: throw IOException("Invalid download URL")
        if (parsedUrl.scheme !in listOf("http", "https")) throw IOException("Unsupported download URL")
        root.mkdirs()
        val partial = File(root, ".partial-${UUID.randomUUID()}")
        try {
            var nextUrl = parsedUrl
            var requestHeaders = headers
            var redirectCount = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                when (val result = fetch(nextUrl, requestHeaders, partial)) {
                    is FetchResult.Redirect -> {
                        if (++redirectCount > MAX_REDIRECTS) throw IOException("Too many download redirects")
                        val target = nextUrl.resolve(result.location) ?: throw IOException("Invalid redirect URL")
                        if (target.scheme !in listOf("http", "https") ||
                            (nextUrl.scheme == "https" && target.scheme != "https")) {
                            throw IOException("Unsafe download redirect")
                        }
                        if (target.host != nextUrl.host || target.port != nextUrl.port || target.scheme != nextUrl.scheme) {
                            requestHeaders = emptyMap()
                        }
                        nextUrl = target
                    }
                    is FetchResult.Saved -> {
                        val entry = DownloadEntry(result.sha256, safeName, result.sizeBytes, clockMillis(), safeProvenance)
                        return@withContext mutex.withLock { commit(partial, entry) }
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            throw IOException("Download redirect failed")
        } finally {
            partial.delete()
        }
    }

    private sealed class FetchResult {
        data class Redirect(val location: String) : FetchResult()
        data class Saved(val sha256: String, val sizeBytes: Long) : FetchResult()
    }

    private suspend fun fetch(url: okhttp3.HttpUrl, headers: Map<String, String>, partial: File): FetchResult {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.build()
        val call = client.newCall(request)
        val callbackDone = CompletableDeferred<Unit>()
        try {
            return suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                try {
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            try {
                                partial.delete()
                                if (continuation.isActive) continuation.resumeWithException(e)
                            } finally {
                                callbackDone.complete(Unit)
                            }
                        }

                        override fun onResponse(call: Call, response: Response) {
                            try {
                                val result = response.use { readResponse(it, partial) }
                                if (continuation.isActive) continuation.resume(result) else partial.delete()
                            } catch (e: Exception) {
                                partial.delete()
                                if (continuation.isActive) continuation.resumeWithException(e)
                            } finally {
                                callbackDone.complete(Unit)
                            }
                        }
                    })
                } catch (e: Exception) {
                    callbackDone.complete(Unit)
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        } finally {
            // Cancellation resumes the coroutine before OkHttp has necessarily closed the file.
            withContext(NonCancellable) { callbackDone.await() }
        }
    }

    private fun readResponse(response: Response, partial: File): FetchResult {
        if (response.code in 300..399) {
            return FetchResult.Redirect(response.header("Location") ?: throw IOException("Redirect lacks Location"))
        }
        if (!response.isSuccessful) throw IOException("Download HTTP ${response.code}")
        if (response.header("Content-Type").orEmpty().startsWith("text/html", ignoreCase = true)) {
            throw IOException("Download returned HTML")
        }
        val body = response.body ?: throw IOException("Download has no body")
        if (body.contentLength() > maxBytes) throw IOException("Download exceeds size limit")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        body.byteStream().use { input ->
            partial.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var firstChunk = true
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (firstChunk && looksLikeHtml(buffer, count)) throw IOException("Download returned HTML")
                    firstChunk = false
                    size += count
                    if (size > maxBytes) throw IOException("Download exceeds size limit")
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
        }
        if (size == 0L) throw IOException("Download is empty")
        if (body.contentLength() >= 0 && size != body.contentLength()) throw IOException("Download is incomplete")
        val hash = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        return FetchResult.Saved(hash, size)
    }

    suspend fun entries(): List<DownloadEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!root.exists()) return@withLock emptyList()
            val directories = root.listFiles() ?: throw IOException("Cannot read downloads directory")
            directories.mapNotNull { directory ->
                if (!directory.isDirectory || !HASH_PATTERN.matches(directory.name)) return@mapNotNull null
                readEntry(directory)
            }.sortedByDescending { it.createdAtMillis }
        }
    }

    fun fileFor(entry: DownloadEntry): File {
        if (!HASH_PATTERN.matches(entry.sha256) || safeFileName(entry.fileName) != entry.fileName) {
            throw IllegalArgumentException("Invalid download entry path")
        }
        val canonicalRoot = root.canonicalFile
        val expectedDirectory = File(canonicalRoot, entry.sha256)
        if (expectedDirectory.canonicalFile != expectedDirectory.absoluteFile) {
            throw IOException("Download directory is a symbolic link")
        }
        val file = File(expectedDirectory, entry.fileName)
        if (file.canonicalFile.parentFile != expectedDirectory) throw IOException("Unsafe download path")
        if (file.exists() && file.canonicalFile != file.absoluteFile) throw IOException("Download file is a symbolic link")
        return file
    }

    suspend fun delete(entry: DownloadEntry): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = fileFor(entry)
            val directory = file.parentFile ?: throw IOException("Invalid download path")
            val stored = readEntry(directory) ?: return@withLock false
            if (stored != entry) return@withLock false
            if (!file.delete()) return@withLock false
            File(directory, INDEX_FILE).delete()
            directory.delete()
            true
        }
    }

    private fun commit(partial: File, entry: DownloadEntry): DownloadEntry {
        val directory = File(root, entry.sha256)
        readEntry(directory)?.let { return it } // First provenance/name wins for identical bytes.
        val metadata = entry.toJson().toString()
        if (metadata.toByteArray(Charsets.UTF_8).size > MAX_INDEX_BYTES) throw IOException("Download metadata is too large")
        if (!directory.exists() && !directory.mkdir()) throw IOException("Cannot create download directory")
        val destination = fileFor(entry)
        if (destination.exists()) destination.delete()
        if (!partial.renameTo(destination)) throw IOException("Cannot save download")
        val index = File(directory, INDEX_FILE)
        val temporaryIndex = File(directory, ".$INDEX_FILE-${UUID.randomUUID()}")
        try {
            temporaryIndex.writeText(metadata, Charsets.UTF_8)
            if (!temporaryIndex.renameTo(index)) throw IOException("Cannot save download metadata")
        } catch (e: Exception) {
            destination.delete()
            throw e
        } finally {
            temporaryIndex.delete()
        }
        return entry
    }

    private fun readEntry(directory: File): DownloadEntry? {
        if (!directory.exists()) return null
        if (directory.canonicalFile != directory.absoluteFile) throw IOException("Download directory is a symbolic link")
        val files = directory.listFiles() ?: throw IOException("Cannot read download directory")
        // A crash before the metadata rename can leave a hash directory with no index.
        val index = files.firstOrNull { it.name == INDEX_FILE } ?: return null
        if (!index.isFile || index.canonicalFile != index.absoluteFile || index.length() > MAX_INDEX_BYTES) {
            throw IOException("Invalid download metadata")
        }
        return try {
            val json = JSONObject(index.readText(Charsets.UTF_8))
            val p = json.getJSONObject("provenance")
            val entry = DownloadEntry(
                json.getString("sha256"), json.getString("fileName"), json.getLong("sizeBytes"),
                json.getLong("createdAtMillis"), DownloadProvenance(
                    p.getString("site"), p.getString("pageUrl"), p.getString("title"),
                    p.optStringOrNull("author"), p.optStringOrNull("license"), p.optStringOrNull("thumbnailUrl"),
                ),
            )
            val file = fileFor(entry)
            if (entry.sha256 != directory.name || !file.isFile || file.length() != entry.sizeBytes) {
                throw IOException("Download metadata does not match its file")
            }
            entry
        } catch (e: org.json.JSONException) {
            throw IOException("Invalid download metadata", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("Invalid download metadata", e)
        }
    }

    private fun DownloadEntry.toJson() = JSONObject()
        .put("sha256", sha256).put("fileName", fileName).put("sizeBytes", sizeBytes)
        .put("createdAtMillis", createdAtMillis).put("provenance", JSONObject()
            .put("site", provenance.site).put("pageUrl", provenance.pageUrl).put("title", provenance.title)
            .put("author", provenance.author).put("license", provenance.license)
            .put("thumbnailUrl", provenance.thumbnailUrl))

    private fun JSONObject.optStringOrNull(key: String): String? = (opt(key) as? String)?.takeIf { it.isNotBlank() }

    private fun safeFileName(name: String): String {
        val base = name.replace('\\', '/').substringAfterLast('/').trim()
            .replace(Regex("[<>:\"|?*\\p{Cntrl}]"), "_")
        if (base.isEmpty() || base == "." || base == "..") throw IllegalArgumentException("Invalid filename")
        val extension = when {
            base.endsWith(".stl", ignoreCase = true) -> base.takeLast(4)
            base.endsWith(".3mf", ignoreCase = true) -> base.takeLast(4)
            else -> null
        }
        if (extension == null) {
            throw IllegalArgumentException("Only STL and 3MF downloads are supported")
        }
        return if (base.length <= 128) base else base.dropLast(extension.length).take(128 - extension.length) + extension
    }

    private fun publicUrl(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid provenance URL")
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) throw IllegalArgumentException("Credential in URL")
        return parsed.newBuilder().query(null).fragment(null).build().toString()
    }

    private fun looksLikeHtml(bytes: ByteArray, count: Int): Boolean {
        val prefix = String(bytes, 0, minOf(count, 256), Charsets.UTF_8).trimStart().lowercase(Locale.ROOT)
        return prefix.startsWith("<!doctype html") || prefix.startsWith("<html")
    }

    private companion object {
        val HASH_PATTERN = Regex("[0-9a-f]{64}")
        const val INDEX_FILE = "metadata.json"
        const val MAX_INDEX_BYTES = 64 * 1024L
        const val MAX_REDIRECTS = 5
    }
}
