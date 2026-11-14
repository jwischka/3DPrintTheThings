package com.u1.slicer.browse

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSiteTest {
    @Test
    fun `five configured sites can be found by stable id`() {
        assertEquals(listOf("makerworld", "printables", "thingiverse", "thangs", "cults3d"),
            ModelSites.all.map { it.id })
        ModelSites.all.forEach { site ->
            assertEquals(site, ModelSites.find(site.id))
            assertTrue(site.ownsPage(site.startUrl))
        }
        assertNull(ModelSites.find("unknown"))
    }

    @Test
    fun `search query is encoded as a complete unicode query or path segment`() {
        val query = "café 猫 + benchy"

        val makerWorld = ModelSites.find("makerworld")!!.searchUrl(query).toHttpUrl()
        assertEquals("/en/search/models", makerWorld.encodedPath)
        assertEquals(query, makerWorld.queryParameter("keyword"))

        val printables = ModelSites.find("printables")!!.searchUrl(query).toHttpUrl()
        assertEquals(query, printables.queryParameter("q"))
        assertEquals("/search/models", printables.encodedPath)

        val thingiverse = ModelSites.find("thingiverse")!!.searchUrl(query).toHttpUrl()
        assertEquals("/search", thingiverse.encodedPath)
        assertEquals(query, thingiverse.queryParameter("q"))

        val thangs = ModelSites.find("thangs")!!.searchUrl(query).toHttpUrl()
        assertEquals(query, thangs.pathSegments.last())
        assertEquals("thangs", thangs.queryParameter("scope"))
        assertEquals("list", thangs.queryParameter("view"))

        val cults = ModelSites.find("cults3d")!!.searchUrl(query).toHttpUrl()
        assertEquals("/en/search", cults.encodedPath)
        assertEquals(query, cults.queryParameter("q"))
    }

    @Test
    fun `site ownership accepts exact host and real subdomains only`() {
        val printables = ModelSites.find("printables")!!

        assertTrue(printables.ownsPage("https://printables.com/search/models?q=benchy"))
        assertTrue(printables.ownsPage("https://www.printables.com/model/123"))
        assertTrue(printables.ownsPage("https://cdn.printables.com/file.3mf"))
        assertFalse(printables.ownsPage("https://printables.com.evil.test/model/123"))
        assertFalse(printables.ownsPage("https://evilprintables.com/model/123"))
        assertFalse(printables.ownsPage("http://www.printables.com/model/123"))
        assertFalse(printables.ownsPage("https://user:pass@www.printables.com/model/123"))
        assertFalse(printables.ownsPage("not a URL"))
        assertEquals("printables", ModelSites.forUrl("https://www.printables.com/model/123")?.id)
        assertNull(ModelSites.forUrl("https://printables.com.evil.test/model/123"))
    }

    @Test
    fun `known auth hosts open externally but lookalikes and unsafe URLs do not`() {
        assertTrue(shouldOpenExternalAuthUrl("https://accounts.google.com/o/oauth2"))
        assertTrue(shouldOpenExternalAuthUrl("https://sub.accounts.google.com/login"))
        assertTrue(shouldOpenExternalAuthUrl("https://appleid.apple.com/auth"))
        assertTrue(shouldOpenExternalAuthUrl("https://www.facebook.com/login"))
        assertTrue(shouldOpenExternalAuthUrl("https://m.facebook.com/login"))
        assertFalse(shouldOpenExternalAuthUrl("https://accounts.google.com.evil.test/"))
        assertFalse(shouldOpenExternalAuthUrl("https://evilfacebook.com/"))
        assertFalse(shouldOpenExternalAuthUrl("http://accounts.google.com/"))
        assertFalse(shouldOpenExternalAuthUrl("https://user@accounts.google.com/"))
        assertFalse(shouldOpenExternalAuthUrl("invalid"))
    }

    @Test
    fun `RFC5987 filename takes precedence and decodes unicode`() {
        val name = resolveDownloadFilename(
            contentDisposition = "attachment; filename=\"fallback.stl\"; filename*=UTF-8''caf%C3%A9%20%E7%8C%AB.3mf",
            sourceUrl = "https://files.example/model.obj",
            fallback = "download.bin",
        )

        assertEquals("café 猫.3mf", name)
    }

    @Test
    fun `filename parameter precedes URL and fallback names`() {
        assertEquals(
            "part.zip",
            resolveDownloadFilename(
                contentDisposition = "attachment; filename=part.zip",
                sourceUrl = "https://files.example/other.3mf",
                fallback = "model.stl",
            ),
        )
        assertEquals(
            "boat.stl",
            resolveDownloadFilename(null, "https://files.example/boat.stl?token=ignored", "download.bin"),
        )
        assertEquals("download.3mf", resolveDownloadFilename(null, null, "download.3mf"))
    }

    @Test
    fun `filename strips traversal and unsafe characters but keeps supported extension`() {
        assertEquals(
            "unsafe name.3mf",
            resolveDownloadFilename("attachment; filename=\"../../../../unsafe name.3mf\"", null, "fallback.stl"),
        )
        assertEquals(
            "evil_.STL",
            resolveDownloadFilename("attachment; filename=..\\..\\evil?.STL", null, "fallback.3mf"),
        )
        assertEquals("assembly.zip", resolveDownloadFilename("attachment; filename*=UTF-8''assembly.zip", null, "x.stl"))
    }
}
