package com.u1.slicer.presets

import com.u1.slicer.network.MoonrakerClient
import com.u1.slicer.network.U1FilamentSnapshot
import com.u1.slicer.ui.printer.experimental.PrinterConnection
import kotlinx.coroutines.runBlocking
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

class FullPresetPrintServiceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun sendsExactBytesAndExplicitFullIdentityAfterTwoPreflights() = runBlocking {
        FakePrinter().use { printer ->
            val job = job(printer, "Snow #2; white & blue.gcode")
            val review = printer.service.review(job) as FullPresetPrintResult.Ready
            assertEquals(job.confirmed.snapshot.slots, review.current.snapshot.slots)
            assertEquals(PREFLIGHT, printer.paths())
            printer.requests.clear()

            val progress = CopyOnWriteArrayList<FullPresetPrintProgress>()
            assertEquals(FullPresetPrintResult.Started, printer.service.send(job) { progress.add(it) })
            assertEquals(PREFLIGHT + listOf(UPLOAD) + PREFLIGHT + listOf(WEBSOCKET), printer.paths())
            assertTrue(printer.requests.all { it.getHeader("X-Api-Key") == API_KEY })
            val body = printer.requests.single { it.path == UPLOAD }.body.clone().readUtf8()
            assertTrue(body.contains("\r\n\r\n${job.file.readText()}\r\n"))
            assertTrue(body.contains("name=\"root\"") && body.contains("\r\n\r\ngcodes\r\n"))
            assertTrue(body.contains("name=\"print\"") && body.contains("\r\n\r\nfalse\r\n"))
            assertTrue(body.contains("name=\"checksum\"") && body.contains("\r\n\r\n${job.sha256}\r\n"))
            val remoteName = requireNotNull(FILENAME.find(body)).groupValues[1]
            assertTrue(remoteName.startsWith("Snow_2_white_blue-"))
            val command = printer.commands.single()
            assertEquals("server.files.start_local_print", command.getString("method"))
            val params = command.getJSONObject("params")
            assertEquals(remoteName, params.getString("path"))
            assertEquals("gcode", params.getString("type"))
            assertEquals("[[0,0],[1,1],[2,2],[3,3]]", params.getJSONObject("options").getJSONArray("map_table").toString())
            assertEquals(FullPresetPrintProgress.Checking, progress.first())
            assertEquals(FullPresetPrintProgress.Uploading(job.sizeBytes, job.sizeBytes),
                progress.filterIsInstance<FullPresetPrintProgress.Uploading>().last())
            assertEquals(FullPresetPrintProgress.Starting, progress.last())
        }
    }

    @Test fun rejectsBusyPrinterBeforeUploadAndChangedFilamentAfterUpload() = runBlocking {
        FakePrinter().use { printer ->
            val job = job(printer)
            printer.machineState = 2
            assertEquals(failure(FullPresetPrintFailure.PRINTER_NOT_READY), printer.service.send(job) {})
            assertEquals(PREFLIGHT.take(2), printer.paths())
            printer.requests.clear()
            printer.machineState = 0
            printer.changeFilamentOnUpload = true
            assertEquals(failure(FullPresetPrintFailure.FILAMENTS_CHANGED), printer.service.send(job) {})
            assertEquals(PREFLIGHT + listOf(UPLOAD) + PREFLIGHT, printer.paths())
            assertTrue(printer.commands.isEmpty())
        }
    }

    @Test fun refusesDifferentOrMissingMaterialFamily() = runBlocking {
        FakePrinter().use { printer ->
            val job = job(printer)
            val mixed = listOf("PLA", "PLA", "PETG", "PLA")
            assertEquals(failure(FullPresetPrintFailure.MATERIAL_MISMATCH),
                printer.service.review(job.copy(materialTypesBySlot = mixed)))
            assertEquals(failure(FullPresetPrintFailure.MATERIAL_MISMATCH),
                printer.service.send(job.copy(materialTypesBySlot = null)) {})
            printer.slotMaterial = "unknown"
            assertEquals(failure(FullPresetPrintFailure.MATERIAL_MISMATCH), printer.service.send(job(printer)) {})
            assertTrue(printer.paths().all { it in PREFLIGHT })
        }
    }

    @Test fun refusesChangedOutputOrMismatchedUploadReceiptWithoutStarting() = runBlocking {
        FakePrinter().use { printer ->
            val job = job(printer)
            val original = job.file.readBytes()
            job.file.writeBytes(original.copyOf().apply { this[0] = 'M'.code.toByte() })
            assertEquals(failure(FullPresetPrintFailure.OUTPUT_CHANGED), printer.service.send(job) {})
            assertTrue(printer.requests.isEmpty())
            job.file.writeBytes(original)
            printer.receiptFilename = "different.gcode"
            assertEquals(failure(FullPresetPrintFailure.UPLOAD_UNCONFIRMED), printer.service.send(job) {})
            assertEquals(PREFLIGHT + listOf(UPLOAD), printer.paths())
            assertTrue(printer.commands.isEmpty())
        }
    }

    @Test fun acceptsWrappedReceiptAndDoesNotReplayAmbiguousFirmwareError() = runBlocking {
        FakePrinter().use { printer ->
            val job = job(printer)
            printer.wrapUploadReceipt = true
            printer.uncertainFirmwareError = true
            assertEquals(FullPresetPrintResult.Uncertain, printer.service.send(job) {})
            assertEquals(1, printer.paths().count { it == WEBSOCKET })
            assertEquals(1, printer.commands.size)
        }
    }

    @Test fun printerSwitchDuringUploadDoesNotStartEitherPrinter() = runBlocking {
        FakePrinter().use { printer ->
            val job = job(printer)
            printer.invalidateConnectionOnUpload = true
            assertEquals(failure(FullPresetPrintFailure.CONNECTION_CHANGED), printer.service.send(job) {})
            assertEquals(PREFLIGHT + listOf(UPLOAD), printer.paths())
            assertTrue(printer.commands.isEmpty())
        }
    }

    private fun job(printer: FakePrinter, filename: String = "Snowflake.gcode"): FullPresetPrintJob {
        val file = temporary.newFile().apply { writeText("T1\nG1 X5 E1\nT2\nG1 X10 E1\n") }
        val content = file.readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
        val slots = requireNotNull(U1FilamentSnapshot.parse(printer.slots().getJSONObject("result")
            .getJSONObject("status"), 0, 0))
        return FullPresetPrintJob(file, filename, FullPresetPrinterFilaments.Ready(printer.connection, slots),
            setOf(2, 3), hash, content.size.toLong(), listOf("PLA", "PLA", "PLA", "PLA"))
    }

    private fun failure(reason: FullPresetPrintFailure) = FullPresetPrintResult.Failed(reason)

    private class FakePrinter : Closeable {
        private val server = MockWebServer()
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val commands = CopyOnWriteArrayList<JSONObject>()
        @Volatile var machineState = 0
        @Volatile var changeFilamentOnUpload = false
        @Volatile var invalidateConnectionOnUpload = false
        @Volatile var receiptFilename: String? = null
        @Volatile var wrapUploadReceipt = false
        @Volatile var uncertainFirmwareError = false
        @Volatile var slotMaterial = "pla"
        @Volatile private var current = true
        val connection = PrinterConnection(server.url("/proxy").toString(), true, 4)
        private val client = MoonrakerClient().apply {
            baseUrl = connection.endpoint
            configureApiKey(API_KEY)
        }
        val service = FullPresetPrintService(
            clientIfCurrent = { if (current && it == connection) client else null },
            isCurrent = { current && it == connection },
            nowElapsedMillis = { 0 },
        )

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add(request)
                    return when (request.path) {
                        PREFLIGHT[0] -> json("""{"result":{"klippy_connected":true,"klippy_state":"ready"}}""")
                        PREFLIGHT[1] -> json(
                            """{"result":{"status":{"webhooks":{"state":"ready"},"print_stats":{"state":"standby"},"machine_state_manager":{"main_state":$machineState}}}}""",
                        )
                        PREFLIGHT[2] -> json(slots().toString())
                        UPLOAD -> upload(request)
                        WEBSOCKET -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                            override fun onMessage(webSocket: WebSocket, text: String) {
                                val command = JSONObject(text)
                                commands.add(command)
                                val requested = command.getJSONObject("params").getString("path")
                                val result = if (uncertainFirmwareError) JSONObject().put("state", "error")
                                    else JSONObject().put("state", "success").put("filename", requested)
                                webSocket.send(JSONObject().put("jsonrpc", "2.0").put("id", command.getInt("id"))
                                    .put("result", result).toString())
                            }
                        })
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        }

        fun paths() = requests.map { it.path }

        fun slots(): JSONObject {
            val color = if (changeFilamentOnUpload && requests.any { it.path == UPLOAD }) "FF0000FF" else "FFFFFFFF"
            return JSONObject("""{"result":{"status":{"print_task_config":{"filament_exist":[false,true,true,false],
                "filament_type":[null,"$slotMaterial","PLA",null],"filament_color_rgba":[null,"$color","0000FFFF",null]}}}}""")
        }

        private fun upload(request: RecordedRequest): MockResponse {
            val payload = request.body.clone().readUtf8()
            val filename = FILENAME.find(payload)?.groupValues?.get(1) ?: return MockResponse().setResponseCode(400)
            val marker = payload.indexOf("\r\n\r\n", payload.indexOf("filename="))
            val end = payload.indexOf("\r\n--", marker + 4)
            val size = payload.substring(marker + 4, end).toByteArray().size
            if (invalidateConnectionOnUpload) current = false
            val receipt = JSONObject()
                .put("item", JSONObject().put("path", receiptFilename ?: filename).put("root", "gcodes").put("size", size))
                .put("print_started", false)
                .put("print_queued", false)
            return json((if (wrapUploadReceipt) JSONObject().put("result", receipt) else receipt).toString())
                .setResponseCode(201)
        }

        private fun json(body: String) = MockResponse().setBody(body)

        override fun close() = server.close()
    }

    private companion object {
        const val API_KEY = "mock-key"
        const val UPLOAD = "/proxy/server/files/upload"
        const val WEBSOCKET = "/proxy/websocket"
        val FILENAME = Regex("filename=\"([A-Za-z0-9_-]+\\.gcode)\"")
        val PREFLIGHT = listOf(
            "/proxy/server/info",
            "/proxy/printer/objects/query?webhooks&print_stats&machine_state_manager=main_state",
            "/proxy/printer/objects/query?${U1FilamentSnapshot.OBJECT_QUERY}",
        )
    }
}

