package com.u1.slicer.network

import com.u1.slicer.network.MoonrakerJsonRead.Reply
import okhttp3.OkHttpClient

/** Read-only check immediately before a Moonraker print start. */
internal class MoonrakerPreflight(client: OkHttpClient) {
    private val read = MoonrakerJsonRead(client)

    suspend fun check(baseUrl: String, requireU1Idle: Boolean = false): String? {
        if (baseUrl.isBlank()) return "No printer URL configured"
        val info = when (val reply = read.get("$baseUrl/server/info")) {
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

        val u1Query = if (requireU1Idle) "&machine_state_manager=main_state" else ""
        val status = when (val reply = read.get("$baseUrl/printer/objects/query?webhooks&print_stats$u1Query")) {
            is Reply.Failure -> return reply.message
            is Reply.Success -> reply.json.optJSONObject("result")?.optJSONObject("status")
                ?: return "Printer status is missing"
        }
        val webhooks = status.optJSONObject("webhooks") ?: return "Printer readiness is missing"
        if (webhooks.optString("state") != "ready") return "Printer is not ready"
        val printStats = status.optJSONObject("print_stats") ?: return "Print status is missing"
        if (requireU1Idle) {
            val machineState = status.optJSONObject("machine_state_manager")?.opt("main_state")
            if (machineState != 0 && machineState != "0" && machineState != "IDLE") {
                return "Snapmaker U1 is not idle"
            }
        }
        return when (printStats.optString("state")) {
            "standby", "complete", "error" -> null // Error is the previous job's terminal state.
            "printing" -> "Printer is already printing"
            "paused" -> "A print is paused"
            "cancelled" -> if (requireU1Idle) null else "Print state is unknown"
            else -> "Print state is unknown"
        }
    }
}
