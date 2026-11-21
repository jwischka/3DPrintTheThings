package com.u1.slicer.ui.printer.experimental

import android.content.Context
import com.u1.slicer.data.MoonrakerApiKeyStore
import com.u1.slicer.network.MoonrakerClient
import java.io.IOException

internal data class PrinterConnection(
    val endpoint: String = "",
    val hasApiKey: Boolean = false,
    val revision: Long = 0,
)

/** Kept separate from the installed printer list and the slicer application container. */
internal class ExperimentalPrinterConnection(context: Context) {
    private val prefs = context.getSharedPreferences("experimental-printer", Context.MODE_PRIVATE)
    private val credentials = MoonrakerApiKeyStore(context.noBackupFilesDir)

    fun load(): PrinterConnection = synchronized(connectionLock) {
        val endpoint = prefs.getString(ENDPOINT, null).orEmpty()
        PrinterConnection(endpoint, credentials.get(PRINTER_ID, endpoint) != null, connectionRevision)
    }

    fun save(endpoint: String, apiKey: String, clearApiKey: Boolean): PrinterConnection = synchronized(connectionLock) {
        val validated = validateEndpoint(endpoint)
        require(validated.isNotEmpty() && validKey(apiKey))
        val previous = load()
        val changed = previous.endpoint != validated
        if (changed && !writeEndpoint(validated)) {
            writeEndpoint(previous.endpoint)
            throw IOException("Printer URL could not be saved")
        }
        var saved = false
        try {
            when {
                apiKey.isNotBlank() -> credentials.put(PRINTER_ID, validated, apiKey)
                clearApiKey || changed -> credentials.remove(PRINTER_ID)
            }
            saved = true
        } finally {
            if (changed && !saved) writeEndpoint(previous.endpoint)
        }
        if (changed || apiKey.isNotBlank() || clearApiKey) connectionRevision++
        load()
    }

    private fun writeEndpoint(endpoint: String): Boolean =
        prefs.edit().putString(ENDPOINT, endpoint.takeIf(String::isNotEmpty)).commit()

    fun isCurrent(connection: PrinterConnection): Boolean = load() == connection

    fun clientIfCurrent(connection: PrinterConnection): MoonrakerClient? = synchronized(connectionLock) {
        if (!isCurrent(connection)) return@synchronized null
        MoonrakerClient().apply {
            baseUrl = connection.endpoint
            configureApiKey(credentials.get(PRINTER_ID, connection.endpoint))
        }
    }

    companion object {
        private const val ENDPOINT = "endpoint"
        private const val PRINTER_ID = "experimental-printer-dashboard"
        private val connectionLock = Any()
        // Prepare and Printer may hold distinct instances in the same app process.
        private var connectionRevision = 0L

        fun validateEndpoint(input: String): String =
            MoonrakerClient.normalizeUrl(input).takeIf { it.length <= 4096 }.orEmpty()

        fun validKey(input: String): Boolean = input.length <= 1024 && input.all { it in ' '..'~' }
    }
}
