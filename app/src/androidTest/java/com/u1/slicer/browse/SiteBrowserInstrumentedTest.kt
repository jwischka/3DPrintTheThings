package com.u1.slicer.browse

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.CookieManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real WebView and Compose, with every HTTPS fixture response served locally. No slicer or printer calls. */
@RunWith(AndroidJUnit4::class)
class SiteBrowserInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()
    private val site = ModelSite("fixture", "Fixture", "https://fixture.test/model/one",
        allowedHosts = setOf("fixture.test"), searchPath = "/search")
    private val browserView = AtomicReference<WebView>()

    @Before fun requireMessageListener() {
        assumeTrue("WebView does not support origin-restricted messages",
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
    }

    @Test fun blobAfterSpaNavigationIsDeliveredOnceAndFramesCannotStartTransfer() {
        val downloads = AtomicInteger()
        val result = AtomicReference<BrowserDownload.Local>()
        val bytes = AtomicReference<String>()
        val delivered = CountDownLatch(1)
        composeRule.setContent {
            SiteBrowserScreen(site = site, onDownload = { download ->
                downloads.incrementAndGet()
                if (download is BrowserDownload.Local) {
                    result.set(download)
                    bytes.set(download.tempFile.readText())
                }
                delivered.countDown()
            }, onBack = {}, requestInterceptor = ::fixtureResponse,
                onWebViewCreated = browserView::set)
        }
        waitForBridge()
        val view = browserView.get()
        composeRule.waitUntil(10_000) {
            evaluate(view, "document.getElementById('same').dataset.loaded === '1' && " +
                "document.getElementById('evil').dataset.loaded === '1'") == "true"
        }
        evaluate(view, "document.getElementById('same').contentWindow.postMessage('attack','*');" +
            "document.getElementById('evil').contentWindow.postMessage('attack','*'); true")
        composeRule.waitUntil(10_000) {
            evaluate(view, "window.frameReports.same === true && window.frameReports.evil === false") == "true"
        }
        evaluate(view, "history.pushState({}, '', '/model/after'); true")
        composeRule.waitUntil(10_000) { evaluate(view, "location.pathname === '/model/after'") == "true" }
        evaluate(view, "window.makeBlob(); true")
        assertTrue("Blob was not delivered", delivered.await(10, TimeUnit.SECONDS))
        assertEquals(1, downloads.get())
        assertTrue(result.get() != null)
        assertEquals("fixture.stl", result.get().fileName)
        assertEquals("https://fixture.test/model/after", result.get().pageUrl)
        assertEquals("solid fixture\nendsolid fixture\n", bytes.get())
    }

    @Test fun disposingDuringLocalImportDeletesCompletedTempFile() {
        val visible = mutableStateOf(true)
        val entered = CountDownLatch(1)
        val temporary = AtomicReference<File>()
        composeRule.setContent {
            if (visible.value) {
                SiteBrowserScreen(site = site, onDownload = { download ->
                    if (download is BrowserDownload.Local) {
                        temporary.set(download.tempFile)
                        entered.countDown()
                        awaitCancellation()
                    }
                }, onBack = {}, requestInterceptor = ::fixtureResponse,
                    onWebViewCreated = browserView::set)
            }
        }
        waitForBridge()
        evaluate(browserView.get(), "window.makeBlob(); true")
        assertTrue("Local import did not start", entered.await(10, TimeUnit.SECONDS))
        assertTrue(temporary.get().exists())
        composeRule.runOnUiThread { visible.value = false }
        composeRule.waitUntil(10_000) { !temporary.get().exists() }
        assertFalse(temporary.get().exists())
    }

    @Test fun contentDispositionDownloadUsesTargetCookiesAndUserAgentOnce() {
        assertNetworkDownload("model", "fixture-model.stl")
    }

    @Test fun zipContentDispositionIsHandedOffAsZipOnce() {
        assertNetworkDownload("zip", "fixture-bundle.zip")
    }

    private fun assertNetworkDownload(kind: String, expectedName: String) {
        val downloads = AtomicInteger()
        val result = AtomicReference<BrowserDownload.Network>()
        val delivered = CountDownLatch(1)
        val cookiesSet = CountDownLatch(2)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            CookieManager.getInstance().apply {
                setCookie("https://fixture.test/", "fixture_cookie=fixture_value; Path=/; Secure") { cookiesSet.countDown() }
                setCookie("https://evil.test/", "other_cookie=other_value; Path=/; Secure") { cookiesSet.countDown() }
            }
        }
        assertTrue("Fixture cookies were not set", cookiesSet.await(10, TimeUnit.SECONDS))
        composeRule.setContent {
            SiteBrowserScreen(site = site, onDownload = { download ->
                downloads.incrementAndGet()
                if (download is BrowserDownload.Network) result.set(download)
                delivered.countDown()
            }, onBack = {}, requestInterceptor = ::fixtureResponse,
                onWebViewCreated = browserView::set)
        }
        waitForBridge()
        val view = browserView.get()
        val userAgent = AtomicReference<String>()
        composeRule.runOnUiThread { userAgent.set(view.settings.userAgentString) }
        evaluate(view, "window.location.href = '/fixture-download?kind=$kind'; true")
        assertTrue("Content-Disposition download was not handed off", delivered.await(10, TimeUnit.SECONDS))
        composeRule.waitForIdle()
        assertEquals(1, downloads.get())
        val download = result.get()
        assertTrue("Expected network download", download != null)
        assertEquals("https://fixture.test/fixture-download?kind=$kind", download.url)
        assertEquals(expectedName, download.fileName)
        assertEquals("https://fixture.test/model/one", download.pageUrl)
        assertEquals(userAgent.get(), download.userAgent)
        assertTrue(download.cookie.orEmpty().contains("fixture_cookie=fixture_value"))
        assertFalse(download.cookie.orEmpty().contains("other_cookie=other_value"))
    }

    private fun waitForBridge() {
        composeRule.waitUntil(10_000) {
            val view = browserView.get() ?: return@waitUntil false
            runCatching { evaluate(view, "Boolean(window.__browserBlobCapture)") == "true" }.getOrDefault(false)
        }
    }

    private fun evaluate(view: WebView, script: String): String {
        val done = CountDownLatch(1)
        val answer = AtomicReference<String>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.evaluateJavascript(script) { value -> answer.set(value); done.countDown() }
        }
        assertTrue("JavaScript did not reply", done.await(5, TimeUnit.SECONDS))
        return answer.get()
    }

    private fun fixtureResponse(request: WebResourceRequest): WebResourceResponse {
        if (request.url.host == "fixture.test" && request.url.path == "/fixture-download") {
            val filename = if (request.url.getQueryParameter("kind") == "zip") "fixture-bundle.zip" else "fixture-model.stl"
            return WebResourceResponse(
                "application/octet-stream", null, 200, "OK",
                mapOf("Content-Disposition" to "attachment; filename=\"$filename\""),
                ByteArrayInputStream("fixture".toByteArray()),
            )
        }
        val html = when (request.url.host to request.url.path) {
            "fixture.test" to "/model/one" -> MAIN_PAGE
            "fixture.test" to "/same" -> SAME_ORIGIN_FRAME
            "evil.test" to "/evil" -> CROSS_ORIGIN_FRAME
            else -> ""
        }
        return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray()))
    }

    private companion object {
        val MAIN_PAGE = """<!doctype html><html><body>
            <iframe id="same" src="https://fixture.test/same" onload="this.dataset.loaded='1'"></iframe>
            <iframe id="evil" src="https://evil.test/evil" onload="this.dataset.loaded='1'"></iframe>
            <script>
                window.frameReports = {};
                window.addEventListener('message', function(event) {
                    if(event.data && event.data.frame) window.frameReports[event.data.frame] = event.data.bridge;
                });
                window.makeBlob = function() {
                    const blob = new Blob(['solid fixture\nendsolid fixture\n'], {type:'model/stl'});
                    const link = document.createElement('a');
                    link.href = URL.createObjectURL(blob);
                    link.download = 'fixture.stl';
                    document.body.appendChild(link);
                    link.click();
                };
            </script></body></html>"""
        val SAME_ORIGIN_FRAME = """<!doctype html><html><script>
            window.addEventListener('message', function() {
                const present = typeof BrowserBlobTransfer !== 'undefined';
                if(present) BrowserBlobTransfer.postMessage(JSON.stringify({
                    type:'begin', nonce:parent.__browserBlobNonce, name:'iframe.stl', size:1
                }));
                parent.postMessage({frame:'same', bridge:present}, '*');
            });
        </script></html>"""
        val CROSS_ORIGIN_FRAME = """<!doctype html><html><script>
            window.addEventListener('message', function() {
                parent.postMessage({frame:'evil', bridge:typeof BrowserBlobTransfer !== 'undefined'}, '*');
            });
        </script></html>"""
    }
}
