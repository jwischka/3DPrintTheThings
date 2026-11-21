package com.u1.slicer.browse

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Thumbnails belong to the active Browse state and can be discarded when it is closed. */
internal class PrintablesThumbnailLoader {
    private val requests = PrintablesThumbnailRequests()
    private val permits = Semaphore(3)
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    suspend fun load(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        return permits.withPermit {
            cache.get(url)?.let { return@withPermit it }
            val bytes = requests.fetch(url) ?: return@withPermit null
            val bitmap = withContext(Dispatchers.Default) { decodeThumbnail(bytes) } ?: return@withPermit null
            cache.put(url, bitmap)
            bitmap
        }
    }

    private fun decodeThumbnail(bytes: ByteArray): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth !in 1..4096 || options.outHeight !in 1..4096) return null

        options.inJustDecodeBounds = false
        var sample = 1
        while (maxOf(options.outWidth, options.outHeight) / sample > 512) sample *= 2
        options.inSampleSize = sample
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}

/** Uses a separate anonymous client for the CDN that supplies the native Printables catalog. */
internal class PrintablesThumbnailRequests(http: OkHttpClient = OkHttpClient()) {
    private val client = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(rawUrl: String): ByteArray? {
        val url = rawUrl.toHttpUrlOrNull() ?: return null
        if (!url.isHttps || url.host != "media.printables.com" || url.port != 443 ||
            url.username.isNotEmpty() || url.password.isNotEmpty()
        ) return null

        val request = Request.Builder().url(url).build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    val bytes = try {
                        response.use { if (continuation.isActive) readImage(it) else null }
                    } catch (_: IOException) {
                        null
                    }
                    if (continuation.isActive) continuation.resume(bytes)
                }
            })
        }
    }

    private fun readImage(response: Response): ByteArray? {
        if (!response.isSuccessful) return null
        val body = response.body ?: return null
        if (body.contentType()?.type != "image" || body.contentLength() > MAX_IMAGE_BYTES) return null

        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        body.byteStream().use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > MAX_IMAGE_BYTES - output.size()) return null
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray().takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
    }
}
