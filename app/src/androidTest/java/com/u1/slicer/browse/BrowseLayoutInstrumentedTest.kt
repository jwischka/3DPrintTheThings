package com.u1.slicer.browse

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.u1.slicer.browse.printables.PrintablesClient
import com.u1.slicer.browse.printables.PrintablesPrint
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress

@RunWith(AndroidJUnit4::class)
class BrowseLayoutInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun resultsAndDownloadStayReachableInShortViewportAtLargeText() {
        val state = BrowsePrintablesState().apply {
            results = (1..6).map { PrintablesPrint("$it", "Model $it", null, null, "Fixture author") }
        }
        var request: Pair<String, String>? = null
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val endpoint = "http://127.0.0.1:${server.port}/graphql"
            val client = PrintablesClient(OkHttpClient(), endpoint)
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {"data":{"print":{"id":"6","name":"Model 6","stls":[{"id":"cube","name":"cube.stl","fileSize":42}],
                    "gcodes":[],"otherFiles":[]}}}
                    """.trimIndent()
                ))
            compose.setContent {
                val largeText = Density(LocalDensity.current.density, fontScale = 2f)
                CompositionLocalProvider(LocalDensity provides largeText) {
                    MaterialTheme {
                        // A short window keeps the test independent of device orientation and display size.
                        Box(Modifier.widthIn(max = 640.dp).fillMaxWidth().height(240.dp)) {
                            BrowseScreen(
                                client = client,
                                onBrowseSite = { _, _ -> },
                                onLibrary = null,
                                onBack = {},
                                onDownloadRequested = { model, file -> request = model.id to file.id },
                                printablesState = state,
                            )
                        }
                    }
                }
            }

            val content = compose.onNodeWithTag("browse-content")
            content.performScrollToNode(hasText("Model 6"))
            compose.onNodeWithText("Model 6").assertIsDisplayed().performClick()
            compose.waitUntil(10_000) { state.detail?.print?.id == "6" }

            content.performScrollToNode(hasText("Download and open"))
            compose.onNodeWithText("Download and open").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals("6" to "cube", request) }

            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Model 6").assertIsDisplayed()
        }
    }
}
