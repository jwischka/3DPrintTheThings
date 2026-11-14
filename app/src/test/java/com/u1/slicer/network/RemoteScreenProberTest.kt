package com.u1.slicer.network

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteScreenProberTest {
    @Test
    fun `probe requests screen and accepts successful response`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200))
            assertTrue(RemoteScreenProber().probe(server.url("/screen/").toString()))
            assertEquals("/screen/", server.takeRequest().path)
        }
    }

    @Test
    fun `probe rejects missing screen and invalid address`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            assertFalse(RemoteScreenProber().probe(server.url("/screen/").toString()))
        }
        assertFalse(RemoteScreenProber().probe(null))
        assertFalse(RemoteScreenProber().probe("invalid"))
    }
}
