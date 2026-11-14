package com.u1.slicer.network

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class U1FilamentSlotsTest {
    private fun response(exists: String, colors: String = "[\"FF0000FF\",\"00FF00FF\",\"0000FFFF\",\"FFFFFFFF\"]") =
        """{"result":{"status":{"print_task_config":{
            "filament_exist":$exists,"filament_color_rgba":$colors,
            "filament_type":["PLA","PETG","ABS","TPU"],
            "filament_sub_type":["NONE","","CF","GF"]
        }}}}"""

    private suspend fun query(payload: String): List<FilamentSlot> = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody(payload))
        val client = MoonrakerClient().apply { baseUrl = server.url("/").toString() }
        val result = client.queryFilamentSlots()
        assertEquals("/printer/objects/query?print_task_config=&filament_detect=", server.takeRequest().path)
        result ?: error("Expected U1 filament slots")
    }

    @Test
    fun `firmware boolean presence array identifies loaded spools`() = runTest {
        val slots = query(response("[true,false,true,true]"))
        assertEquals(listOf(true, false, true, true), slots.map { it.loaded })
        assertEquals(listOf(0, 1, 2, 3), slots.map { it.index })
        assertEquals("#FF0000", slots[0].color)
    }

    @Test
    fun `legacy numeric and string presence encodings still work`() = runTest {
        val slots = query(response("[1,0,\"true\",\"1\"]"))
        assertEquals(listOf(true, false, true, true), slots.map { it.loaded })
    }

    @Test
    fun `presence controls slot count when colors are shorter`() = runTest {
        val slots = query(response("[true,false,true,true]", "[\"FF0000FF\"]"))
        assertEquals(4, slots.size)
        assertTrue(slots[2].loaded)
    }

    @Test
    fun `null and unrecognized presence values are empty`() = runTest {
        val slots = query(response("[null,\"false\",\"unknown\",false]"))
        assertTrue(slots.none { it.loaded })
    }

    @Test
    fun `missing presence falls back to color count without claiming spools loaded`() = runTest {
        val slots = query(response("null"))
        assertEquals(4, slots.size)
        assertFalse(slots.any { it.loaded })
    }
}
