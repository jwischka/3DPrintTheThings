package com.u1.slicer.network

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

internal enum class U1PrintStartResult { Started, Rejected, NotSent, Uncertain }

/** The U1 advanced start API accepts the tool routing and the file in one WebSocket request. */
internal suspend fun startU1LocalFile(
    client: OkHttpClient,
    request: Request,
    filename: String,
    maySend: () -> Boolean,
): U1PrintStartResult {
    val dispatched = AtomicBoolean()
    val result = withTimeoutOrNull(25_000L) {
        suspendCancellableCoroutine<U1PrintStartResult> { continuation ->
            val complete = AtomicBoolean()
            val socket = AtomicReference<WebSocket?>()
            continuation.invokeOnCancellation { socket.get()?.cancel() }
            val map = JSONArray((0..3).map { JSONArray(listOf(it, it)) })
            val command = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "server.files.start_local_print")
                .put("params", JSONObject().put("path", filename).put("type", "gcode")
                    .put("options", JSONObject().put("map_table", map)))
                .toString()

            fun finish(webSocket: WebSocket, outcome: U1PrintStartResult) {
                if (!complete.compareAndSet(false, true)) return
                if (continuation.isActive) continuation.resume(outcome)
                webSocket.cancel()
            }

            val connection = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!continuation.isActive || !runCatching(maySend).getOrDefault(false)) {
                        finish(webSocket, U1PrintStartResult.NotSent)
                        return
                    }
                    dispatched.set(true)
                    if (!webSocket.send(command)) {
                        dispatched.set(false)
                        finish(webSocket, U1PrintStartResult.NotSent)
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!dispatched.get() || text.length > 16_384) return
                    val reply = runCatching { JSONObject(text) }.getOrNull() ?: return
                    if (reply.optString("jsonrpc") != "2.0" || reply.opt("id") != 1) return
                    val response = reply.optJSONObject("result")
                    val error = reply.optJSONObject("error")
                    val outcome = when {
                        error == null && response != null && response.optString("state") == "success" &&
                            response.optString("filename") == filename -> U1PrintStartResult.Started
                        error?.optInt("code") == -32601 -> U1PrintStartResult.Rejected
                        else -> U1PrintStartResult.Uncertain
                    }
                    finish(webSocket, outcome)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    finish(webSocket, lostResponse(dispatched))
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    finish(webSocket, lostResponse(dispatched))
                }
            })
            socket.set(connection)
            if (!continuation.isActive) connection.cancel()
        }
    }
    return result ?: lostResponse(dispatched)
}

private fun lostResponse(dispatched: AtomicBoolean): U1PrintStartResult =
    if (dispatched.get()) U1PrintStartResult.Uncertain else U1PrintStartResult.NotSent

