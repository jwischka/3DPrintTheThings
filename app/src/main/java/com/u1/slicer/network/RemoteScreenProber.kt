package com.u1.slicer.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

internal class RemoteScreenProber {
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    suspend fun probe(url: String?): Boolean = withContext(Dispatchers.IO) {
        if (url == null) return@withContext false
        try {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { it.isSuccessful }
        } catch (_: java.io.IOException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
