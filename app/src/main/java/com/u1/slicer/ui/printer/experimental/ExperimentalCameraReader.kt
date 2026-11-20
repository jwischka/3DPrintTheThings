package com.u1.slicer.ui.printer.experimental

import com.u1.slicer.network.MoonrakerClient
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Cache the working URL and periodically check for a webcam while using a stored image. */
internal class ExperimentalCameraReader(
    private val client: MoonrakerClient,
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val printerUrl = client.baseUrl.toHttpUrlOrNull()
    private var workingUrl: HttpUrl? = null
    private var monitorDiscoveryMillis = 0L

    data class Frame<T>(val image: T, val isStoredMonitorFile: Boolean)

    suspend fun <T : Any> nextFrame(decode: suspend (ByteArray) -> T?): Frame<T>? {
        val printer = printerUrl ?: return null
        val previousUrl = workingUrl
        val recheckWebcam = previousUrl?.isStoredMonitorFile() == true &&
            elapsedMillis() - monitorDiscoveryMillis >= MONITOR_DISCOVERY_INTERVAL_MS
        if (previousUrl != null && !recheckWebcam) {
            val frame = fetch(previousUrl, decode)
            if (frame != null) return frame
        }
        workingUrl = null

        val candidates = client.queryWebcamSnapshotCandidates().mapNotNull { it.toHttpUrlOrNull() }
        val urls = if (recheckWebcam && previousUrl != null) candidates + previousUrl else candidates
        for (url in urls.distinct().sortedBy { it.isStoredMonitorFile() }) {
            if (url == previousUrl && !recheckWebcam) continue
            // Snapmaker serves relative webcam URLs on the printer's web port.
            if (url.host != printer.host || (printer.isHttps && !url.isHttps)) continue
            val frame = fetch(url, decode) ?: continue
            workingUrl = url
            if (frame.isStoredMonitorFile) monitorDiscoveryMillis = elapsedMillis()
            return frame
        }
        return null
    }

    private suspend fun <T : Any> fetch(url: HttpUrl, decode: suspend (ByteArray) -> T?): Frame<T>? {
        val bytes = client.fetchSnapshot(url.toString()) ?: return null
        val image = decode(bytes) ?: return null
        return Frame(image, url.isStoredMonitorFile())
    }

    private fun HttpUrl.isStoredMonitorFile() = encodedPath.endsWith("/server/files/camera/monitor.jpg")

    private companion object {
        const val MONITOR_DISCOVERY_INTERVAL_MS = 15_000L
    }
}
