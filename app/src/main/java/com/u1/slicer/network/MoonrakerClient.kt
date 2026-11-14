package com.u1.slicer.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * HTTP client for Moonraker API (Klipper web interface).
 * Used to communicate with Snapmaker U1 printer.
 *
 * Ported from u1-slicer-bridge: moonraker.py
 */
class MoonrakerClient(private val baseClient: OkHttpClient = defaultClient()) {
    private val TAG = "MoonrakerClient"
    private val remoteScreenProber = RemoteScreenProber()
    private val authLock = Any()
    @Volatile private var client: OkHttpClient = baseClient
    @Volatile private var apiKeyScope: ApiKeyScope? = null

    private class ApiKeyScope(val base: HttpUrl, val key: String) {
        fun includes(url: HttpUrl): Boolean {
            if (url.scheme != base.scheme || url.host != base.host || url.port != base.port) return false
            val prefix = base.encodedPath.trimEnd('/')
            return prefix.isEmpty() || url.encodedPath == prefix || url.encodedPath.startsWith("$prefix/")
        }
    }

    var baseUrl: String = ""
        set(value) {
            val normalized = normalizeUrl(value)
            synchronized(authLock) {
                if (normalized != field) {
                    apiKeyScope = null
                    client = baseClient
                }
                field = normalized
            }
        }

    /** Bind a key to the currently configured Moonraker origin and route prefix. */
    fun configureApiKey(key: String?) {
        if (key != null) {
            require(key.length in 1..1024 && key.all { it.code in 32..126 } && key.isNotBlank()) {
                "Invalid Moonraker API key"
            }
        }
        val normalizedKey = key?.trim()
        synchronized(authLock) {
            if (normalizedKey == null) {
                apiKeyScope = null
                client = baseClient
                return
            }
            val base = baseUrl.toHttpUrlOrNull() ?: throw IllegalArgumentException("Configure a printer URL first")
            val scope = ApiKeyScope(base, normalizedKey)
            val scopedClient = baseClient.newBuilder()
                .addNetworkInterceptor { chain ->
                    val request = chain.request()
                    val builder = request.newBuilder().removeHeader("X-Api-Key")
                    if (scope.includes(request.url)) builder.header("X-Api-Key", scope.key)
                    chain.proceed(builder.build())
                }
                .build()
            apiKeyScope = scope
            client = scopedClient
        }
    }

    /**
     * Query Moonraker's webcam list and return snapshot URL candidates for the first webcam.
     * Returns up to 2 candidates: [primary, alt] where alt keeps the original port.
     * Falls back to legacy mjpeg-streamer path if the list is unavailable.
     * Mirrors the bridge's moonraker.py get_webcams() + _resolve_moonraker_url() logic.
     * Always appends monitor.jpg as a final fallback candidate.
     */
    suspend fun queryWebcamSnapshotCandidates(): List<String> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext emptyList()
        val monitorUrl = "$baseUrl/server/files/camera/monitor.jpg"
        try {
            val request = Request.Builder().url(url("/server/webcams/list")).get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            response.close()
            if (response.isSuccessful && body != null) {
                val webcams = org.json.JSONObject(body)
                    .getJSONObject("result")
                    .getJSONArray("webcams")
                if (webcams.length() > 0) {
                    val cam = webcams.getJSONObject(0)
                    val rawUrl = cam.optString("snapshot_url", "")
                        .ifBlank { cam.optString("snapshotUrl", "") }
                    if (rawUrl.isNotBlank()) {
                        val primary = resolveWebcamUrl(rawUrl, keepPort = false)
                        val alt = resolveWebcamUrl(rawUrl, keepPort = true)
                        val candidates = listOfNotNull(
                            primary.ifBlank { null },
                            alt.takeIf { it.isNotBlank() && it != primary }
                        )
                        if (candidates.isNotEmpty()) {
                            return@withContext candidates + monitorUrl
                        }
                    }
                }
            }
        } catch (_: Exception) {
            Log.d(TAG, "Webcam list unavailable, using legacy fallback")
        }
        // Legacy fallback: mjpeg-streamer style + monitor.jpg
        listOf("$baseUrl/webcam/?action=snapshot", monitorUrl)
    }

    /**
     * Wake the printer's camera keepalive stream by opening a WebSocket connection
     * and sending camera.start_monitor. Fire-and-forget: returns before the WebSocket
     * handshake completes; the message is sent asynchronously on OkHttp's dispatcher thread.
     * Used to ensure the camera monitor.jpg endpoint is freshly populated.
     */
    suspend fun wakeCamera() = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext
        try {
            val wsUrl = when {
                baseUrl.startsWith("https://") -> "wss://" + baseUrl.removePrefix("https://")
                else -> "ws://" + baseUrl.removePrefix("http://")
            } + "/websocket"
            val requestBuilder = Request.Builder().url(wsUrl)
            apiKeyScope?.takeIf { it.includes(requestBuilder.build().url) }
                ?.let { requestBuilder.header("X-Api-Key", it.key) }
            val request = requestBuilder.build()
            client.newBuilder().followRedirects(false).followSslRedirects(false)
                .build().newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"jsonrpc":"2.0","id":1000,"method":"camera.start_monitor","params":{"domain":"lan","interval":0}}""")
                    webSocket.close(1000, null)
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.d(TAG, "wakeCamera failed")
                }
            })
        } catch (_: Exception) {
            Log.d(TAG, "wakeCamera failed")
        }
    }

    /** Resolve a possibly-relative webcam URL against baseUrl. Mirrors bridge's _resolve_moonraker_url(). */
    private fun resolveWebcamUrl(value: String, keepPort: Boolean): String {
        if (value.isBlank()) return ""
        // Already absolute
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        // Relative — resolve against base, optionally stripping the port
        return try {
            val base = java.net.URI(baseUrl)
            val host = if (keepPort) base.host + (if (base.port != -1) ":${base.port}" else "")
                       else base.host
            "${base.scheme}://$host/${value.trimStart('/')}"
        } catch (_: Exception) {
            "$baseUrl/${value.trimStart('/')}"
        }
    }

    companion object {
        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        /** Bare HTTP printer hosts use 7125; explicit paths and HTTPS keep their normal ports. */
        fun normalizeUrl(raw: String): String {
            val value = raw.trim()
            if (value.isEmpty()) return ""
            if ("://" in value && !value.startsWith("http://", true) && !value.startsWith("https://", true)) return ""
            val candidate = if (value.startsWith("http://", true) || value.startsWith("https://", true)) value else "http://$value"
            val uri = runCatching { java.net.URI(candidate) }.getOrNull() ?: return ""
            val parsed = candidate.toHttpUrlOrNull() ?: return ""
            if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
                uri.host == null || parsed.scheme !in setOf("http", "https")) return ""
            val hasPath = !uri.rawPath.isNullOrEmpty() && uri.rawPath != "/"
            if (uri.port != -1) return "${parsed.scheme}://${uri.rawAuthority}${parsed.encodedPath}".trimEnd('/')
            val port = if (uri.port == -1 && parsed.scheme == "http" && !hasPath) 7125 else parsed.port
            return parsed.newBuilder().port(port).build().toString().trimEnd('/')
        }

        /**
         * Build a SET_HEATER_TEMPERATURE G-code string with clamped target.
         * Bed is clamped to [0, 120]; extruders to [0, 300].
         */
        internal fun buildSetHeaterGcode(heater: String, targetC: Int): String {
            val isBed = heater == "heater_bed"
            val clamped = targetC.coerceIn(0, if (isBed) 120 else 300)
            return "SET_HEATER_TEMPERATURE HEATER=$heater TARGET=$clamped"
        }
    }

    private fun url(path: String): String = "$baseUrl$path"

    /** Null when Klippy is ready and no print is active; a reason otherwise. */
    suspend fun validateStartJob(): String? = MoonrakerPreflight(client).check(baseUrl)

    /** Fetch a camera snapshot with the same scoped auth policy as Moonraker HTTP calls. */
    suspend fun fetchSnapshot(snapshotUrl: String): ByteArray? {
        val parsed = snapshotUrl.toHttpUrlOrNull() ?: return null
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
        val request = Request.Builder().url(parsed).get().build()
        val snapshotClient = client.newBuilder()
            .callTimeout(3, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
        return suspendCancellableCoroutine { continuation ->
            val call = snapshotClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    val bytes = try {
                        response.use {
                            val body = response.body
                            if (!response.isSuccessful || body == null || body.contentLength() > 8_388_608L) {
                                null
                            } else {
                                val source = body.source()
                                source.request(8_388_609L)
                                if (source.buffer.size > 8_388_608L) null
                                else source.buffer.clone().readByteArray().takeIf { it.isNotEmpty() }
                            }
                        }
                    } catch (_: IOException) { null }
                    if (continuation.isActive) continuation.resume(bytes)
                }
            })
        }
    }

    /**
     * Test connection to the printer.
     * @return null on success, or a human-readable error string on failure.
     */
    suspend fun testConnection(): String? = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext "No URL configured"
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            return@withContext "URL must start with http:// or https://"
        }
        try {
            val request = Request.Builder().url(url("/server/info")).get().build()
            val response = client.newCall(request).execute()
            val ok = response.isSuccessful
            val code = response.code
            response.close()
            if (ok) null else "HTTP $code — is Moonraker running on this address?"
        } catch (e: java.net.ConnectException) {
            "Connection refused — check IP and port (Moonraker default: 7125)"
        } catch (e: java.net.SocketTimeoutException) {
            "Timed out — check printer is on and reachable"
        } catch (e: java.net.UnknownHostException) {
            "Unknown host — check URL"
        } catch (e: Exception) {
            Log.w(TAG, "Connection test failed")
            "Connection failed"
        }
    }

    /**
     * Returns the URL to the paxx12 extended-firmware remote screen endpoint, or null if
     * the host cannot be determined from [baseUrl].
     * The screen is served by nginx on port 80 (not Moonraker's 7125).
     */
    fun remoteScreenUrl(): String? {
        if (baseUrl.isBlank()) return null
        return try {
            val uri = java.net.URI(baseUrl)
            "${uri.scheme}://${uri.host}/screen/"
        } catch (_: Exception) { null }
    }

    /**
     * Probes `http://<host>/screen/` (paxx12 extended firmware).
     * @return true if the endpoint responds with HTTP 2xx, false otherwise.
     */
    suspend fun probeRemoteScreen(): Boolean = remoteScreenProber.probe(remoteScreenUrl())

    /**
     * Get printer status: temps, state, progress.
     */
    suspend fun getStatus(): PrinterStatus = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext PrinterStatus(state = "disconnected", progress = 0f)
        try {
            val queryParams = "print_stats&virtual_sdcard&heater_bed&extruder&extruder1&extruder2&extruder3"
            val request = Request.Builder()
                .url(url("/printer/objects/query?$queryParams"))
                .get().build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return@withContext PrinterStatus(state = "error", progress = 0f)
            }

            val json = JSONObject(response.body?.string() ?: "{}")
            response.close()
            val result = json.optJSONObject("result")?.optJSONObject("status")
                ?: return@withContext PrinterStatus(state = "standby", progress = 0f)

            val printStats = result.optJSONObject("print_stats")
            val virtualSdcard = result.optJSONObject("virtual_sdcard")
            val heaterBed = result.optJSONObject("heater_bed")
            val extruder = result.optJSONObject("extruder")

            // Parse extruders 1-3
            val extruders = mutableListOf<ExtruderStatus>()
            extruder?.let {
                extruders.add(ExtruderStatus(0,
                    it.optDouble("temperature", 0.0).toFloat(),
                    it.optDouble("target", 0.0).toFloat(),
                    it.optDouble("target", 0.0) > 0
                ))
            }
            for (i in 1..3) {
                result.optJSONObject("extruder$i")?.let {
                    extruders.add(ExtruderStatus(i,
                        it.optDouble("temperature", 0.0).toFloat(),
                        it.optDouble("target", 0.0).toFloat(),
                        it.optDouble("target", 0.0) > 0
                    ))
                }
            }

            // Prefer virtual_sdcard.progress — it tracks actual file read position and
            // stays accurate throughout the print.  print_stats.progress can stick at 0
            // on some Moonraker/Klipper builds (B33).  Fall back to print_stats.progress
            // if virtual_sdcard is absent (older firmware).
            val progress = (virtualSdcard?.optDouble("progress")
                ?: printStats?.optDouble("progress"))?.toFloat() ?: 0f
            PrinterStatus(
                state = printStats?.optString("state", "standby") ?: "standby",
                progress = progress,
                filename = printStats?.optString("filename", "") ?: "",
                printDuration = printStats?.optDouble("print_duration", 0.0)?.toFloat() ?: 0f,
                filamentUsed = printStats?.optDouble("filament_used", 0.0)?.toFloat() ?: 0f,
                nozzleTemp = extruder?.optDouble("temperature", 0.0)?.toFloat() ?: 0f,
                nozzleTarget = extruder?.optDouble("target", 0.0)?.toFloat() ?: 0f,
                bedTemp = heaterBed?.optDouble("temperature", 0.0)?.toFloat() ?: 0f,
                bedTarget = heaterBed?.optDouble("target", 0.0)?.toFloat() ?: 0f,
                extruders = extruders
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get status")
            PrinterStatus(state = "disconnected", progress = 0f)
        }
    }

    /**
     * Query per-extruder filament information from the printer.
     * Primary source: print_task_config (Snapmaker U1 firmware).
     * Fallback: AFC (Automatic Filament Changer) Klipper objects.
     * Returns null if neither source is available.
     */
    suspend fun queryFilamentSlots(): List<FilamentSlot>? = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext null
        // Try print_task_config first (native Snapmaker U1 source)
        val fromTask = queryPrintTaskConfig()
        if (!fromTask.isNullOrEmpty()) return@withContext fromTask
        // Fallback to AFC
        queryAfcSlots()
    }

    private suspend fun queryPrintTaskConfig(): List<FilamentSlot>? {
        return try {
            val request = Request.Builder()
                .url(url("/printer/objects/query?print_task_config=&filament_detect="))
                .get().build()
            val json = queryFilamentJson(request) ?: return null

            val status = json.optJSONObject("result")?.optJSONObject("status") ?: return null
            val ptc = status.optJSONObject("print_task_config") ?: return null

            val colors = ptc.optJSONArray("filament_color_rgba")
            val types = ptc.optJSONArray("filament_type")
            val subTypes = ptc.optJSONArray("filament_sub_type")
            val vendors = ptc.optJSONArray("filament_vendor")
            val exist = ptc.optJSONArray("filament_exist")

            val slotCount = exist?.length()?.takeIf { it > 0 } ?: colors?.length() ?: 0
            if (slotCount == 0) return null

            val slots = mutableListOf<FilamentSlot>()
            for (i in 0 until slotCount) {
                val rgba = colors?.optString(i, "") ?: ""
                // U1 firmware reports booleans; retain older numeric/string encodings.
                val loaded = when (val value = exist?.opt(i)) {
                    is Boolean -> value
                    is Number -> value.toDouble() != 0.0
                    is String -> value.trim().let { it == "1" || it.equals("true", ignoreCase = true) }
                    else -> false
                }
                slots.add(FilamentSlot(
                    index = i,
                    label = "E${i + 1}",
                    color = normalizeRgbaHex(rgba),
                    loaded = loaded,
                    materialType = normalizeMaterialType(types?.optString(i, "PLA") ?: "PLA"),
                    subType = subTypes?.optString(i, "") ?: "",
                    manufacturer = vendors?.optString(i, "") ?: ""
                ))
            }
            slots.takeIf { it.isNotEmpty() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "print_task_config query failed")
            null
        }
    }

    private suspend fun queryAfcSlots(): List<FilamentSlot>? {
        return try {
            // Discover AFC objects via /printer/objects/list
            val listReq = Request.Builder().url(url("/printer/objects/list")).get().build()
            val listJson = queryFilamentJson(listReq) ?: return null

            val objects = listJson.optJSONObject("result")?.optJSONArray("objects") ?: return null
            val afcObjects = mutableListOf<String>()
            for (i in 0 until objects.length()) {
                val name = objects.optString(i)
                if (name.startsWith("AFC_stepper ") || name.startsWith("AFC ")) {
                    afcObjects.add(name)
                }
            }
            if (afcObjects.isEmpty()) return null

            // Query all AFC objects at once
            val queryStr = afcObjects.joinToString("&") { java.net.URLEncoder.encode(it, "UTF-8") + "=" }
            val afcReq = Request.Builder().url(url("/printer/objects/query?$queryStr")).get().build()
            val afcJson = queryFilamentJson(afcReq) ?: return null

            val afcStatus = afcJson.optJSONObject("result")?.optJSONObject("status") ?: return null
            val slots = mutableListOf<FilamentSlot>()
            var idx = 0
            for (objName in afcObjects) {
                val obj = afcStatus.optJSONObject(objName) ?: continue
                val color = obj.optString("color", "").let {
                    if (it.isNotBlank()) "#${it.uppercase().trimStart('#')}" else "#808080"
                }
                val material = normalizeMaterialType(obj.optString("material", "PLA"))
                val loaded = obj.optBoolean("load_state", false) ||
                             obj.optString("status", "") == "Loaded"
                val laneNum = objName.substringAfterLast(" ").filter { it.isDigit() }
                slots.add(FilamentSlot(
                    index = idx++,
                    label = if (laneNum.isNotEmpty()) "E$laneNum" else "E${idx}",
                    color = color,
                    loaded = loaded,
                    materialType = material
                ))
            }
            slots.takeIf { it.isNotEmpty() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "AFC query failed")
            null
        }
    }

    /** Cancel the actual call when a mapping refresh or settings sync is cancelled. */
    private suspend fun queryFilamentJson(request: Request): JSONObject? = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use responseUse@{
                        if (!it.isSuccessful) return@responseUse null
                        val body = it.body ?: return@responseUse null
                        if (body.contentLength() > 1024 * 1024) return@responseUse null
                        val bytes = java.io.ByteArrayOutputStream()
                        body.byteStream().use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (bytes.size() + count > 1024 * 1024) return@responseUse null
                                bytes.write(buffer, 0, count)
                            }
                        }
                        JSONObject(bytes.toString(Charsets.UTF_8.name()))
                    }
                } catch (_: Exception) { null }
                if (continuation.isActive) continuation.resume(result)
            }
        })
    }

    /** Convert Bambu RGBA hex (e.g. "E72F1DFF") to #RRGGBB */
    private fun normalizeRgbaHex(rgba: String): String {
        val cleaned = rgba.trimStart('#').uppercase()
        return when {
            cleaned.length == 8 -> "#${cleaned.take(6)}"  // RRGGBBAA → #RRGGBB
            cleaned.length == 6 -> "#$cleaned"
            else -> "#808080"
        }
    }

    /** Normalize material type strings to canonical forms */
    private fun normalizeMaterialType(raw: String): String {
        return when (raw.uppercase().trim()) {
            "PLA+", "PLA PLUS", "PLAP" -> "PLA"
            "PET-G", "PETG-", "PETG+" -> "PETG"
            "ABS+", "ABS PLUS" -> "ABS"
            "FLEX", "TPU95A", "TPU87A" -> "TPU"
            "HIPS-", "HIPS+" -> "HIPS"
            else -> raw.trim().ifEmpty { "PLA" }
        }
    }

    /** Upload one request. Progress counts file bytes, not multipart framing. */
    suspend fun uploadGcodeResult(
        file: File,
        filename: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): MoonrakerUploadResult {
        if (!file.isFile || file.length() == 0L) return MoonrakerUploadResult.Failure("G-code file is empty or missing")
        val (uploadClientBase, uploadKey, uploadBaseUrl) = synchronized(authLock) {
            Triple(client, apiKeyScope?.key, baseUrl)
        }
        val total = file.length()
        val fileSizeMb = total / (1024 * 1024)
        val timeout = maxOf(30L, minOf(300L, fileSizeMb))
        val uploadClient = uploadClientBase.newBuilder()
            .writeTimeout(timeout, TimeUnit.SECONDS)
            .readTimeout(timeout, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        runCatching { onProgress(0, total) }
        val sourceBody = file.asRequestBody("application/octet-stream".toMediaType())
        val countingBody = object : RequestBody() {
            override fun contentType() = sourceBody.contentType()
            override fun contentLength() = total
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                var sent = 0L
                val countingSink = object : ForwardingSink(sink) {
                    override fun write(source: okio.Buffer, byteCount: Long) {
                        super.write(source, byteCount)
                        sent += byteCount
                        runCatching { onProgress(sent.coerceAtMost(total), total) }
                    }
                }.buffer()
                sourceBody.writeTo(countingSink)
                countingSink.flush()
            }
        }
        val request = try {
            Request.Builder().url("$uploadBaseUrl/server/files/upload")
                .post(MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", filename, countingBody).build())
                .build()
        } catch (_: IllegalArgumentException) {
            return MoonrakerUploadResult.Failure("Invalid printer URL")
        }
        return suspendCancellableCoroutine { continuation ->
            val call = uploadClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(
                        MoonrakerUploadResult.Failure("Upload connection failed")
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use {
                            val source = response.body?.source()
                            val oversized = if (source != null) {
                                source.request(4097)
                                source.buffer.size > 4096L
                            } else false
                            val snippet = if (source != null) {
                                source.buffer.clone().readUtf8(minOf(source.buffer.size, 4097L))
                            } else ""
                            val parsed = runCatching { JSONObject(snippet) }.getOrNull()
                            val message = runCatching {
                                parsed?.optJSONObject("error")?.optString("message")
                                    ?: parsed?.optString("message")
                            }.getOrNull()?.trim()
                                ?.let { raw -> if (uploadKey != null) raw.replace(uploadKey, "[redacted]") else raw }
                                ?.replace(Regex("[\\p{Cntrl}]+"), " ")
                                ?.take(300)?.takeIf { it.isNotBlank() }
                            val confirmed = !oversized && parsed?.optJSONObject("result")?.let { result ->
                                result.optJSONObject("item") != null || (result.has("path") && result.has("root"))
                            } == true
                            if (response.isSuccessful && confirmed && message == null) MoonrakerUploadResult.Success
                            else if (response.isSuccessful) MoonrakerUploadResult.Failure("Could not confirm upload from printer")
                            else MoonrakerUploadResult.Failure(
                                "Upload failed (HTTP ${response.code})" + (if (message != null) ": $message" else "")
                            )
                        }
                    } catch (_: IOException) {
                        MoonrakerUploadResult.Failure("Upload response failed")
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }
    }

    suspend fun uploadGcode(file: File, filename: String): Boolean =
        uploadGcodeResult(file, filename) == MoonrakerUploadResult.Success

    /**
     * Start printing a previously uploaded file.
     */
    suspend fun startPrint(filename: String): Boolean = withContext(Dispatchers.IO) {
        val startUrl = runCatching {
            url("/printer/print/start").toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("filename", filename)?.build()?.toString()
        }.getOrNull() ?: return@withContext false
        postCommand(startUrl, absolute = true)
    }

    suspend fun pausePrint(): Boolean = withContext(Dispatchers.IO) {
        postCommand("/printer/print/pause")
    }

    suspend fun resumePrint(): Boolean = withContext(Dispatchers.IO) {
        postCommand("/printer/print/resume")
    }

    suspend fun cancelPrint(): Boolean = withContext(Dispatchers.IO) {
        postCommand("/printer/print/cancel")
    }

    suspend fun getLedState(): Boolean? = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext null
        try {
            val request = Request.Builder()
                .url(url("/printer/objects/query?led%20cavity_led"))
                .get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            response.close()
            if (!response.isSuccessful || body == null) return@withContext null
            val result = JSONObject(body).optJSONObject("result")?.optJSONObject("status")
            val ledObj = result?.optJSONObject("led cavity_led") ?: return@withContext null
            val colorData = ledObj.optJSONArray("color_data")
            if (colorData != null && colorData.length() > 0) {
                val rgba = colorData.getJSONArray(0)
                // W channel is index 3 for RGBW
                val w = if (rgba.length() > 3) rgba.getDouble(3) else 0.0
                w > 0
            } else null
        } catch (e: Exception) {
            Log.d(TAG, "LED state query failed")
            null
        }
    }

    suspend fun setLed(on: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext false
        val white = if (on) "1.0" else "0"
        val script = "SET_LED LED=cavity_led WHITE=$white RED=0 GREEN=0 BLUE=0"
        try {
            val body = JSONObject().put("script", script).toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url("/printer/gcode/script"))
                .post(body)
                .build()
            val response = client.newCall(request).execute()
            val ok = response.isSuccessful
            response.close()
            ok
        } catch (e: Exception) {
            Log.w(TAG, "Set LED failed")
            false
        }
    }

    /**
     * Send an arbitrary G-code script to the printer via Moonraker's /printer/gcode/script endpoint.
     */
    suspend fun sendGcode(gcode: String): Boolean = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext false
        try {
            val body = org.json.JSONObject().put("script", gcode).toString()
                .toRequestBody("application/json".toMediaType())
            val response = client.newCall(
                Request.Builder().url("$baseUrl/printer/gcode/script").post(body).build()
            ).execute()
            val ok = response.isSuccessful
            response.close()
            ok
        } catch (e: Exception) {
            Log.w(TAG, "sendGcode failed")
            false
        }
    }

    /**
     * Set a heater to a target temperature using Klipper's SET_HEATER_TEMPERATURE command.
     * @param heater  Klipper heater name: "heater_bed", "extruder", "extruder1", etc.
     * @param targetC Target temperature in Celsius (clamped to safe range).
     */
    suspend fun setHeaterTemperature(heater: String, targetC: Int): Boolean =
        sendGcode(buildSetHeaterGcode(heater, targetC))

    private fun postCommand(path: String, absolute: Boolean = false): Boolean {
        return try {
            val request = Request.Builder()
                .url(if (absolute) path else url(path))
                .post(ByteArray(0).toRequestBody(null))
                .build()
            val response = client.newCall(request).execute()
            val ok = response.isSuccessful
            response.close()
            ok
        } catch (e: Exception) {
            Log.w(TAG, "Command failed")
            false
        }
    }
}
