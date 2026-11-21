package com.u1.slicer.presets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FullPresetModelSourceUrlTest {
    @Test
    fun keepsHttpsModelPathAndDropsQueryAndFragment() {
        val page = "https://www.printables.com/model/42-two%20parts"
        assertEquals(page, fullPresetModelSourceUrl("$page?token=secret#files"))
    }

    @Test
    fun omitsUnusableOrCredentialBearingSources() {
        for (url in listOf(
            null,
            "",
            "   ",
            "http://www.printables.com/model/42",
            "https://user:secret@www.printables.com/model/42",
            "file:///model.stl",
            "javascript:alert(1)",
            "https://?",
        )) {
            assertNull(fullPresetModelSourceUrl(url))
        }
    }
}
