package com.u1.slicer.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Read-only check immediately before a Moonraker print start. */
internal class MoonrakerPreflight(client: OkHttpClient) {
    private val http = client.newBuilder()
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    suspend fun check(baseUrl: String): String? {
        if (baseUrl.isBlank()) return "No printer URL configured"
        val info = when (val reply = get("$baseUrl/server/info")) {
            is Reply.Failure -> return reply.message
            is Reply.Success -> reply.json.optJSONObject("result")
                ?: return "Moonraker server status is missing"
        }
        if (!info.optBoolean("klippy_connected", false)) return "Klippy is disconnected"
        when (info.optString("klippy_state")) {
            "ready" -> Unit
            "startup" -> return "Klippy is starting up"
            "error" -> return "Klippy has an error"
            "shutdown" -> return "Klippy is shut down"
            "disconnected" -> return "Klippy is disconnected"
            else -> return "Klippy readiness is unknown"
        }

        val status = when (val reply = get("$baseUrl/printer/objects/query?webhooks&print_stats")) {
            is Reply.Failure -> return reply.message
            is Reply.Success -> reply.json.optJSONObject("result")?.optJSONObject("status")
                ?: return "Printer status is missing"
        }
        val webhooks = status.optJSONObject("webhooks") ?: return "Printer readiness is missing"
        if (webhooks.optString("state") != "ready") return "Printer is not ready"
        val printStats = status.optJSONObject("print_stats") ?: return "Print status is missing"
        return when (printStats.optString("state")) {
            "standby", "complete", "error" -> null // Error is the previous job's terminal state.
            "printing" -> "Printer is already printing"
            "paused" -> "A print is paused"
            else -> "Print state is unknown"
        }
    }

    private sealed interface Reply {
        data class Success(val json: JSONObject) : Reply
        data class Failure(val message: String) : Reply
    }

    private suspend fun get(url: String): Reply {
        val request = try {
            Request.Builder().url(url).get().build()
        } catch (_: IllegalArgumentException) {
            return Reply.Failure("Invalid printer URL")
        }
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(Reply.Failure("Could not reach Moonraker"))
                }

                override fun onResponse(call: Call, response: Response) {
                    val reply = try {
                        response.use {
                            when (response.code) {
                                401, 403 -> Reply.Failure("Moonraker authorization required")
                                in 300..399 -> Reply.Failure("Moonraker redirected the request")
                                else -> if (!response.isSuccessful) {
                                    Reply.Failure("Moonraker returned HTTP ${response.code}")
                                } else {
                                    val source = response.body?.source()
                                        ?: return@use Reply.Failure("Moonraker response is empty")
                                    source.request(16_385)
                                    if (source.buffer.size > 16_384L) {
                                        Reply.Failure("Moonraker response is too large")
                                    } else {
                                        val json = runCatching { JSONObject(source.buffer.clone().readUtf8()) }.getOrNull()
                                        if (json == null) Reply.Failure("Moonraker response is malformed")
                                        else Reply.Success(json)
                                    }
                                }
                            }
                        }
                    } catch (_: IOException) {
                        Reply.Failure("Could not read Moonraker response")
                    }
                    if (continuation.isActive) continuation.resume(reply)
                }
            })
        }
    }
}
