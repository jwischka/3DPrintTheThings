package com.u1.slicer.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedModelLinksTest {
    @Test fun findsKnownModelLinkInsideSharedText() {
        val link = sharedModelLink("A model to print (https://www.printables.com/model/123-cube).")!!
        assertEquals("printables", link.site.id)
        assertEquals("https://www.printables.com/model/123-cube", link.url)
        assertEquals("makerworld", sharedModelLink("https://makerworld.com/en/models/123#profileId-42")!!.site.id)
    }

    @Test fun rejectsImpersonationAndCredentialsAndSkipsUnknownUrls() {
        assertNull(sharedModelLink("https://printables.com.evil.example/model/123"))
        assertNull(sharedModelLink("https://user:password@printables.com/model/123"))
        assertNull(sharedModelLink("http://printables.com/model/123"))
        assertEquals("thingiverse", sharedModelLink("https://example.com https://www.thingiverse.com/thing:123")!!.site.id)
    }

    @Test fun preservesEncodedQueriesAndHandlesEverySite() {
        for (site in ModelSites.all) {
            val url = site.searchUrl("a+b / c")
            assertEquals(url, sharedModelLink(url)!!.url)
            assertEquals(site.id, sharedModelLink(url)!!.site.id)
        }
    }

    @Test fun zipMimeNeverUnpacksThreeMf() {
        assertFalse(isIncomingZip("MODEL.3MF", "application/zip"))
        assertFalse(isIncomingZip("cube.stl", "application/zip"))
        assertTrue(isIncomingZip("models.ZIP", "application/octet-stream"))
        assertTrue(isIncomingZip(null, "application/x-zip-compressed"))
        assertFalse(isIncomingZip(null, "application/octet-stream"))
    }
}
