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

/** Bounded, cancellable reads shared by status polling and print preflight. */
internal class MoonrakerJsonRead(client: OkHttpClient) {
    private val http = client.newBuilder()
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    enum class Problem(val message: String) {
        INVALID_URL("Invalid printer URL"),
        UNREACHABLE("Could not reach Moonraker"),
        AUTHORIZATION("Moonraker authorization required"),
        REDIRECT("Moonraker redirected the request"),
        HTTP("Moonraker returned HTTP"),
        EMPTY("Moonraker response is empty"),
        OVERSIZE("Moonraker response is too large"),
        MALFORMED("Moonraker response is malformed"),
        UNREADABLE("Could not read Moonraker response"),
    }

    sealed interface Reply {
        data class Success(val json: JSONObject) : Reply
        data class Failure(val problem: Problem, val statusCode: Int? = null) : Reply {
            val message: String get() = if (statusCode == null) problem.message else "${problem.message} $statusCode"
        }
    }

    suspend fun get(url: String): Reply {
        val request = try {
            Request.Builder().url(url).get().build()
        } catch (_: IllegalArgumentException) {
            return Reply.Failure(Problem.INVALID_URL)
        }
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(Reply.Failure(Problem.UNREACHABLE))
                }

                override fun onResponse(call: Call, response: Response) {
                    val reply = try {
                        response.use {
                            when (response.code) {
                                401, 403 -> Reply.Failure(Problem.AUTHORIZATION)
                                in 300..399 -> Reply.Failure(Problem.REDIRECT)
                                else -> if (response.isSuccessful) readBody(response)
                                else Reply.Failure(Problem.HTTP, response.code)
                            }
                        }
                    } catch (_: IOException) {
                        Reply.Failure(Problem.UNREADABLE)
                    }
                    if (continuation.isActive) continuation.resume(reply)
                }
            })
        }
    }

    private fun readBody(response: Response): Reply {
        val source = response.body?.source() ?: return Reply.Failure(Problem.EMPTY)
        source.request(MAX_JSON_BYTES + 1)
        if (source.buffer.size > MAX_JSON_BYTES) return Reply.Failure(Problem.OVERSIZE)
        val json = runCatching { JSONObject(source.buffer.clone().readUtf8()) }.getOrNull()
            ?: return Reply.Failure(Problem.MALFORMED)
        return Reply.Success(json)
    }

    private companion object {
        const val MAX_JSON_BYTES = 16_384L
    }
}
