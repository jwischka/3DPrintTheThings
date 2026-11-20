package com.u1.slicer.ui.printer.experimental

import com.u1.slicer.network.MoonrakerClient
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Prefer the last working snapshot URL, and rediscover when its frame becomes unavailable. */
internal class ExperimentalCameraReader(private val client: MoonrakerClient) {
    private val printerUrl = client.baseUrl.toHttpUrlOrNull()
    private var workingUrl: HttpUrl? = null

    data class Frame<T>(val image: T, val isStoredMonitorFile: Boolean)

    suspend fun <T : Any> nextFrame(decode: suspend (ByteArray) -> T?): Frame<T>? {
        val printer = printerUrl ?: return null
        val previousUrl = workingUrl
        if (previousUrl != null) {
            val frame = fetch(previousUrl, decode)
            if (frame != null) return frame
            workingUrl = null
        }

        for (candidate in client.queryWebcamSnapshotCandidates()) {
            val url = candidate.toHttpUrlOrNull() ?: continue
            if (url == previousUrl) continue
            // Snapmaker serves relative webcam URLs on the printer's web port.
            if (url.host != printer.host || (printer.isHttps && !url.isHttps)) continue
            val frame = fetch(url, decode) ?: continue
            workingUrl = url
            return frame
        }
        return null
    }

    private suspend fun <T : Any> fetch(url: HttpUrl, decode: suspend (ByteArray) -> T?): Frame<T>? {
        val bytes = client.fetchSnapshot(url.toString()) ?: return null
        val image = decode(bytes) ?: return null
        return Frame(image, url.encodedPath.endsWith("/server/files/camera/monitor.jpg"))
    }
}
