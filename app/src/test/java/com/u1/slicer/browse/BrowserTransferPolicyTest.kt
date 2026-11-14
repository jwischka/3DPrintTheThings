package com.u1.slicer.browse

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserTransferPolicyTest {
    @Test fun directSignedModelUrlsAreRecognizedWithoutExaminingQueryTokens() {
        assertTrue(isDirectModelDownloadUrl("https://files.example.net/models/part.3mf?signature=private"))
        assertTrue(isDirectModelDownloadUrl("https://cdn.example.net/archive.ZIP?token=private"))
        assertTrue(isDirectModelDownloadUrl("https://cdn.example.net/part.STL"))
    }

    @Test fun pagesAndUnsafeUrlsAreNotClassifiedAsDownloads() {
        assertFalse(isDirectModelDownloadUrl("https://cdn.example.net/download?id=part.3mf"))
        assertFalse(isDirectModelDownloadUrl("https://cdn.example.net/model/123"))
        assertFalse(isDirectModelDownloadUrl("http://cdn.example.net/part.stl"))
        assertFalse(isDirectModelDownloadUrl("https://user:pass@cdn.example.net/part.stl"))
        assertFalse(isDirectModelDownloadUrl("https://cdn.example.net/part.stl.html"))
    }
}
