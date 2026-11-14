package com.u1.slicer.browse

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.u1.slicer.R
import com.u1.slicer.ui.hasAuthCookies
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

sealed interface BrowserDownload {
    val fileName: String
    val pageUrl: String
    val title: String

    data class Network(
        val url: String,
        override val fileName: String,
        override val pageUrl: String,
        override val title: String,
        val userAgent: String,
        val cookie: String?,
    ) : BrowserDownload

    /** The callback must copy/import [tempFile] before returning; the browser then deletes it. */
    data class Local(
        val tempFile: File,
        override val fileName: String,
        override val pageUrl: String,
        override val title: String,
    ) : BrowserDownload
}

/** One in-app browser for site-owned HTTPS pages. Downloads are handed off exactly once. */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiteBrowserScreen(
    site: ModelSite,
    initialUrl: String = site.startUrl,
    onDownload: suspend (BrowserDownload) -> Unit,
    onMakerWorldCookies: (String) -> Unit = {},
    onBack: () -> Unit,
    onLibrary: (() -> Unit)? = null,
    requestInterceptor: ((WebResourceRequest) -> WebResourceResponse?)? = null,
    onWebViewCreated: ((WebView) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var webView by remember(site.id) { mutableStateOf<WebView?>(null) }
    var bridge by remember(site.id) { mutableStateOf<BrowserBlobBridge?>(null) }
    var currentUrl by remember(site.id) { mutableStateOf(site.startUrl) }
    var committedPageUrl by remember(site.id) { mutableStateOf(site.startUrl) }
    var loading by remember(site.id) { mutableStateOf(true) }
    var busy by remember(site.id) { mutableStateOf(false) }
    var activeJob by remember(site.id) { mutableStateOf<Job?>(null) }
    val popups = remember(site.id) { mutableSetOf<WebView>() }
    var error by remember(site.id) { mutableStateOf(false) }
    var externalAuthUrl by remember(site.id) { mutableStateOf<String?>(null) }
    val openExternally = { url: String ->
        if (!openExternalBrowser(context, url)) error = true
    }
    fun handOff(download: BrowserDownload) {
        if (busy) {
            if (download is BrowserDownload.Local) download.tempFile.delete()
            return
        }
        busy = true
        error = false
        val job = scope.launch {
            try {
                onDownload(download)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = true
            } finally {
                busy = false
                activeJob = null
            }
        }
        if (download is BrowserDownload.Local) job.invokeOnCompletion { download.tempFile.delete() }
        activeJob = job
    }

    DisposableEffect(site.id) {
        onDispose {
            activeJob?.cancel()
            bridge?.detach()
            popups.toList().forEach { popup -> popup.stopLoading(); popup.destroy() }
            popups.clear()
            webView?.apply {
                stopLoading()
                webChromeClient = null
                webViewClient = WebViewClient()
                destroy()
            }
            bridge = null
            webView = null
        }
    }

    BackHandler {
        if (busy) {
            activeJob?.cancel()
            bridge?.reset()
            onBack()
            return@BackHandler
        }
        val current = webView
        if (current?.canGoBack() == true) {
            bridge?.cancelTransfer()
            current.goBack()
        } else {
            bridge?.reset()
            onBack()
        }
    }

    externalAuthUrl?.let { target ->
        AlertDialog(
            onDismissRequest = { externalAuthUrl = null },
            title = { Text(stringResource(R.string.browser_external_auth_title)) },
            text = { Text(stringResource(R.string.browser_external_auth_message)) },
            confirmButton = {
                TextButton(onClick = { openExternally(target); externalAuthUrl = null }) {
                    Text(stringResource(R.string.browser_open_external))
                }
            },
            dismissButton = {
                TextButton(onClick = { externalAuthUrl = null }) {
                    Text(stringResource(R.string.browser_cancel))
                }
            },
        )
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(site.name) },
            navigationIcon = {
                IconButton(onClick = {
                    activeJob?.cancel()
                    bridge?.reset()
                    onBack()
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.browser_close))
                }
            },
            actions = {
                if (onLibrary != null) {
                    TextButton(onClick = onLibrary, enabled = !busy) {
                        Text(stringResource(R.string.browser_library))
                    }
                }
                IconButton(onClick = { openExternally(currentUrl) }, enabled = !busy) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = stringResource(R.string.browser_open_external))
                }
            },
        )
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.setSupportMultipleWindows(true)
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        val main = this
                        val blobAssembler = BlobTransferAssembler(File(ctx.cacheDir, "browser-blobs"))
                        val blobBridge = BrowserBlobBridge(
                            view = main, site = site, assembler = blobAssembler,
                            onLocal = { file, name, page ->
                                handOff(BrowserDownload.Local(file, name, page, site.name))
                            },
                            onError = { error = true },
                        )
                        bridge = blobBridge
                        blobBridge.attach()

                        fun routeTopLevel(url: String, beforeNavigation: Boolean = true): Boolean {
                            if (busy) return true
                            if (shouldOpenExternalAuthUrl(url)) {
                                externalAuthUrl = url
                                return true
                            }
                            if (site.ownsPage(url)) return false
                            // Before WebView requests an explicit offsite model file, hand it to
                            // the one download pipeline. Opaque CDN URLs remain external links.
                            if (beforeNavigation && site.ownsPage(committedPageUrl) && isDirectModelDownloadUrl(url)) {
                                val page = publicPageUrl(site, committedPageUrl)
                                if (page != null) {
                                    handOff(BrowserDownload.Network(
                                        url = url,
                                        fileName = resolveDownloadFilename(null, url, "download.bin"),
                                        pageUrl = page,
                                        title = site.name,
                                        userAgent = settings.userAgentString,
                                        cookie = CookieManager.getInstance().getCookie(url),
                                    ))
                                    return true
                                }
                            }
                            openExternally(url)
                            return true
                        }

                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                                request?.let { requestInterceptor?.invoke(it) }
                                    ?: super.shouldInterceptRequest(view, request)

                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                if (request?.isForMainFrame != true) return false
                                return routeTopLevel(request.url.toString())
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                if (url == null || !site.ownsPage(url) || busy) {
                                    view?.stopLoading()
                                    if (url != null) routeTopLevel(url, beforeNavigation = false)
                                    return
                                }
                                blobBridge.navigate(url)
                                currentUrl = url
                                loading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                                if (url == null || !site.ownsPage(url)) return
                                currentUrl = url
                                committedPageUrl = url
                                blobBridge.inject()
                                if (site.id == "makerworld") {
                                    val cookies = CookieManager.getInstance().getCookie("https://makerworld.com").orEmpty()
                                    if (hasAuthCookies(cookies)) onMakerWorldCookies(cookies)
                                }
                            }

                            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                super.doUpdateVisitedHistory(view, url, isReload)
                                if (url != null && site.ownsPage(url)) {
                                    currentUrl = url
                                    committedPageUrl = url
                                }
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onCreateWindow(view: WebView?, isDialog: Boolean,
                                isUserGesture: Boolean, resultMsg: Message?): Boolean {
                                if (!isUserGesture || busy || resultMsg == null) return false
                                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                                val popup = WebView(ctx)
                                popups += popup
                                fun releasePopup() {
                                    if (popups.remove(popup)) { popup.stopLoading(); popup.destroy() }
                                }
                                popup.webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                        if (request?.isForMainFrame != true) return true
                                        val target = request.url.toString()
                                        if (!routeTopLevel(target)) main.loadUrl(target)
                                        releasePopup()
                                        return true
                                    }
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        if (url.isNullOrBlank() || url == "about:blank") return
                                        if (!routeTopLevel(url, beforeNavigation = false)) main.loadUrl(url)
                                        releasePopup()
                                    }
                                }
                                // A popup may close without navigating beyond about:blank.
                                popup.webChromeClient = object : WebChromeClient() {
                                    override fun onCloseWindow(window: WebView?) { releasePopup() }
                                }
                                transport.webView = popup
                                resultMsg.sendToTarget()
                                return true
                            }
                        }

                        setDownloadListener { url, userAgent, disposition, _, _ ->
                            if (busy || !site.ownsPage(committedPageUrl)) return@setDownloadListener
                            if (url.startsWith("blob:", ignoreCase = true)) {
                                blobBridge.capture(url, disposition)
                                return@setDownloadListener
                            }
                            val downloadUrl = url.toHttpUrlOrNull() ?: return@setDownloadListener
                            if (downloadUrl.scheme != "https" || downloadUrl.username.isNotEmpty() ||
                                downloadUrl.password.isNotEmpty()) return@setDownloadListener
                            val page = publicPageUrl(site, committedPageUrl) ?: return@setDownloadListener
                            val filename = resolveDownloadFilename(disposition, url, "download.bin")
                            val cookie = CookieManager.getInstance().getCookie(url)
                            handOff(BrowserDownload.Network(url, filename, page, site.name,
                                userAgent ?: settings.userAgentString, cookie))
                        }
                        loadUrl(initialUrl.takeIf(site::ownsPage) ?: site.startUrl)
                        webView = this
                        onWebViewCreated?.invoke(this)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error) Text(stringResource(R.string.browser_download_failed),
                color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        }
    }
}

private fun publicPageUrl(site: ModelSite, url: String): String? {
    if (!site.ownsPage(url)) return null
    return url.toHttpUrlOrNull()?.newBuilder()?.query(null)?.fragment(null)?.build()?.toString()
}

private const val BLOB_BRIDGE_NAME = "BrowserBlobTransfer"

private class BrowserBlobBridge(
    private val view: WebView,
    private val site: ModelSite,
    private val assembler: BlobTransferAssembler,
    private val onLocal: (File, String, String) -> Unit,
    private val onError: () -> Unit,
) : WebViewCompat.WebMessageListener {
    private var attached = false
    private var nonce = ""
    private var pageUrl = ""
    private var fileName = ""
    private var transferActive = false

    fun attach() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        val origins = site.allowedHosts.flatMap { root ->
            listOf("https://$root", "https://*.$root")
        }.toSet()
        WebViewCompat.addWebMessageListener(view, BLOB_BRIDGE_NAME, origins, this)
        attached = true
    }

    fun detach() {
        reset()
        if (attached) WebViewCompat.removeWebMessageListener(view, BLOB_BRIDGE_NAME)
        attached = false
    }

    fun navigate(url: String) {
        reset()
        pageUrl = url
        nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }

    fun inject() {
        if (!attached || !trusted(nonce)) return
        val quoted = JSONObject.quote(nonce)
        val script = """(function(){
            if(window !== window.top || window.__browserBlobNonce === $quoted) return;
            window.__browserBlobNonce = $quoted;
            const seen = new Set();
            let capturing = false;
            async function send(message) {
                return new Promise(function(resolve) {
                    const timeout = setTimeout(function() {
                        BrowserBlobTransfer.removeEventListener('message', receive);
                        resolve(false);
                    }, 10000);
                    function receive(event) {
                        clearTimeout(timeout);
                        BrowserBlobTransfer.removeEventListener('message', receive);
                        resolve(event.data === 'ok');
                    }
                    BrowserBlobTransfer.addEventListener('message', receive);
                    BrowserBlobTransfer.postMessage(JSON.stringify(message));
                });
            }
            // Blob anchors and WebView download callbacks only; unrelated XHR traffic is not intercepted.
            window.__browserBlobCapture = async function(url, name) {
                if(capturing || typeof url !== 'string' || !url.startsWith('blob:') || seen.has(url)) return;
                capturing = true;
                seen.add(url);
                try {
                    const blob = await (await fetch(url)).blob();
                    if(blob.size <= 0 || blob.size > 33554432) throw new Error('blob size');
                    if(!await send({type:'begin', nonce:$quoted, name:name || 'download.bin', size:blob.size})) return;
                    let index = 0;
                    for(let offset = 0; offset < blob.size; offset += 32768) {
                        const chunk = new Uint8Array(await blob.slice(offset, offset + 32768).arrayBuffer());
                        let binary = '';
                        for(let i = 0; i < chunk.length; i++) binary += String.fromCharCode(chunk[i]);
                        if(!await send({type:'chunk', nonce:$quoted, index:index++, data:btoa(binary)})) return;
                    }
                    await send({type:'finish', nonce:$quoted});
                } catch (_) { await send({type:'abort', nonce:$quoted}); }
                finally { seen.delete(url); capturing = false; }
            };
            document.addEventListener('click', function(event) {
                const target = event.target && event.target.closest ? event.target.closest('a[href^="blob:"]') : null;
                if(target) { event.preventDefault(); window.__browserBlobCapture(target.href, target.download); }
            }, true);
        })();""".trimIndent()
        view.evaluateJavascript(script, null)
    }

    fun capture(url: String, disposition: String?) {
        if (!attached || !trusted(nonce)) { onError(); return }
        val name = resolveDownloadFilename(disposition, null, "download.bin")
        view.evaluateJavascript("window.__browserBlobCapture && window.__browserBlobCapture(${JSONObject.quote(url)}, ${JSONObject.quote(name)})", null)
    }

    fun cancelTransfer() { assembler.reset(); transferActive = false }

    fun reset() { cancelTransfer(); nonce = ""; pageUrl = "" }

    private fun trusted(token: String): Boolean {
        val active = view.url?.toHttpUrlOrNull() ?: return false
        val loaded = pageUrl.toHttpUrlOrNull() ?: return false
        return token.isNotEmpty() && token == nonce && site.ownsPage(view.url.orEmpty()) &&
            active.scheme == loaded.scheme && active.host == loaded.host && active.port == loaded.port
    }

    override fun onPostMessage(view: WebView, message: WebMessageCompat, sourceOrigin: Uri,
        isMainFrame: Boolean, replyProxy: JavaScriptReplyProxy) {
        if (!attached || view !== this.view || !isMainFrame || !trusted(nonce)) return
        val page = pageUrl.toHttpUrlOrNull() ?: return
        val source = sourceOrigin.toString().toHttpUrlOrNull() ?: return
        if (source.scheme != "https" || source.host != page.host || source.port != page.port) return
        val raw = message.data ?: return
        if (raw.length > MAX_BLOB_MESSAGE_LENGTH) { onError(); replyProxy.postMessage("error"); return }
        val command = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (command.optString("nonce") != nonce) return
        val accepted = when (command.optString("type")) {
            "begin" -> {
                val name = command.optString("name")
                val size = command.optLong("size", -1)
                if (transferActive || name.length > 256 || size <= 0 || size > MAX_BLOB_BYTES) false
                else {
                    fileName = resolveDownloadFilename(null, null, name)
                    assembler.begin(nonce, size).also { transferActive = it }
                }
            }
            "chunk" -> transferActive && assembler.append(nonce, command.optInt("index", -1),
                command.optString("data"))
            "finish" -> {
                if (!transferActive) false else {
                    transferActive = false
                    val completed = assembler.complete(nonce)
                    val safePage = view.url?.let { publicPageUrl(site, it) }
                    if (completed == null || safePage == null) { completed?.delete(); false }
                    else {
                        try { onLocal(completed, fileName, safePage); true }
                        catch (_: Exception) { completed.delete(); false }
                    }
                }
            }
            "abort" -> { assembler.reset(); transferActive = false; false }
            else -> false
        }
        if (!accepted) {
            assembler.reset()
            transferActive = false
            onError()
        }
        replyProxy.postMessage(if (accepted) "ok" else "error")
    }

    private companion object {
        const val MAX_BLOB_BYTES = 32L * 1024 * 1024
        const val MAX_BLOB_MESSAGE_LENGTH = 48 * 1024 + 256
    }
}
