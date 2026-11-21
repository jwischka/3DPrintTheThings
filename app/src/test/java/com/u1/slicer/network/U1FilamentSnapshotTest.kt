package com.u1.slicer.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class U1FilamentSnapshotTest {
    @Test
    fun distinguishesLoadedEmptyAndUnknownWithinFourPhysicalSlots() {
        val status = JSONObject("""
            {"print_task_config":{
              "filament_exist":[true,false,null,7,true],
              "filament_vendor":["Example Vendor","NONE",false,"", "Ignored"],
              "filament_type":["PETG",null,"unknown",14,"PLA"],
              "filament_sub_type":["Gloss","",null,"None","Ignored"],
              "filament_color_rgba":["35ab72FF","FFFFFF80","#xyzxyz",null,"12345678"]
            }}
        """.trimIndent())
        val snapshot = requireNotNull(U1FilamentSnapshot.parse(status, 123_456, 2_000))
        assertEquals(listOf(
            U1FilamentSlot(1, true, "Example Vendor", "PETG", "Gloss", "#35AB72"),
            U1FilamentSlot(2, false, null, null, null, "#FFFFFF"),
            U1FilamentSlot(3, null, null, null, null, null),
            U1FilamentSlot(4, null, null, null, null, null),
        ), snapshot.slots)
        assertEquals(123_456L, snapshot.receivedAtMillis)
        assertTrue(snapshot.isFresh(17_000))
        assertFalse(snapshot.isFresh(17_001))
        assertFalse(snapshot.isFresh(1_999))
    }

    @Test
    fun metadataDoesNotImplyPresence() {
        val status = JSONObject("""{"print_task_config":{"filament_type":["TPU"]}}""")
        val firstSlot = requireNotNull(U1FilamentSnapshot.parse(status, 0, 0)).slots.first()
        assertEquals("TPU", firstSlot.material)
        assertNull(firstSlot.present)
        assertNull(firstSlot.color)
        assertNull(U1FilamentSnapshot.parse(JSONObject("""{"print_stats":{"state":"printing"}}"""), 0, 0))
    }

    @Test
    fun sendsOnlyTheReadOnlyFilamentFieldQueryUsingTheConfiguredConnection() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(MockResponse().setBody("""
                {"result":{"status":{"print_task_config":{
                  "filament_exist":[true,false,false,true],
                  "filament_type":["PETG","NONE","NONE","TPU"],
                  "filament_color_rgba":["1234ABFF","FFFFFFFF","FFFFFFFF","ABCD1234"]
                }}}}
            """.trimIndent()))
            server.enqueue(MockResponse().setBody("""{"result":{"status":{}}}"""))
            server.enqueue(MockResponse().setResponseCode(401))
            val client = MoonrakerClient().apply {
                baseUrl = "http://127.0.0.1:${server.port}/moonraker"
                configureApiKey("fixture-read-key")
            }

            val reply = client.readU1FilamentSnapshot() as U1FilamentResult.Received
            assertEquals(listOf(1, 4), reply.snapshot.slots.filter { it.present == true }.map { it.physicalSlot })
            assertEquals(U1FilamentResult.Unavailable(), client.readU1FilamentSnapshot())
            assertEquals(U1FilamentResult.Unavailable(MoonrakerJsonRead.Problem.AUTHORIZATION),
                client.readU1FilamentSnapshot())

            repeat(3) {
                val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals("GET", request.method)
                assertEquals("/moonraker/printer/objects/query?${U1FilamentSnapshot.OBJECT_QUERY}", request.path)
                assertEquals("fixture-read-key", request.getHeader("X-Api-Key"))
            }
        }
    }
}
