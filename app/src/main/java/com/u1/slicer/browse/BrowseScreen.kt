package com.u1.slicer.browse

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.u1.slicer.R
import com.u1.slicer.browse.printables.PrintablesClient
import com.u1.slicer.browse.printables.PrintablesFile
import com.u1.slicer.browse.printables.PrintablesPrint
import com.u1.slicer.browse.printables.PrintablesPrintDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

class BrowsePrintablesState {
    internal var results by mutableStateOf<List<PrintablesPrint>?>(null)
    internal var detail by mutableStateOf<PrintablesPrintDetail?>(null)
    internal var failed by mutableStateOf(false)
    internal val thumbnails by lazy { PrintablesThumbnailLoader() }
}

/** A shared query with native Printables results and site-owned search pages. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    client: PrintablesClient,
    onDownload: (suspend (PrintablesPrint, PrintablesFile, String) -> Unit)? = null,
    onBrowseSite: (ModelSite, String) -> Unit,
    onLibrary: (() -> Unit)?,
    onBack: () -> Unit,
    onDownloadRequested: ((PrintablesPrint, PrintablesFile) -> Unit)? = null,
    downloadsEnabled: Boolean = true,
    printablesState: BrowsePrintablesState = remember { BrowsePrintablesState() },
    @StringRes webSearchFallback: Int? = null,
    onDetailClosed: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    var query by rememberSaveable { mutableStateOf("") }
    var selectedSiteId by rememberSaveable { mutableStateOf("printables") }
    var searchRevision by rememberSaveable { mutableIntStateOf(0) }
    val selectedSite = ModelSites.find(selectedSiteId) ?: ModelSites.all.first()
    val resultsScroll = key(searchRevision) { rememberLazyGridState() }
    val selected = printablesState.detail
    val detailScroll = key(selected?.print?.id) { rememberLazyGridState() }
    val scroll = if (selected == null) resultsScroll else detailScroll
    val siteScroll = rememberScrollState()
    val cardWidth = if (LocalDensity.current.fontScale >= 1.5f) 232.dp else 164.dp
    var busy by remember { mutableStateOf(false) }
    val failed = printablesState.failed
    LaunchedEffect(failed, scroll) {
        if (failed) scroll.scrollToItem(0)
    }

    fun request(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        printablesState.failed = false
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                printablesState.failed = true
            } catch (_: IllegalArgumentException) {
                printablesState.failed = true
            } finally {
                busy = false
            }
        }
    }

    fun back() {
        if (printablesState.detail != null && !busy) {
            printablesState.detail = null
            printablesState.failed = false
            onDetailClosed()
        } else onBack()
    }
    BackHandler { back() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.browse_models)) },
            navigationIcon = {
                IconButton(onClick = { back() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.browse_back))
                }
            },
            actions = {
                if (onLibrary != null) {
                    TextButton(onClick = onLibrary, enabled = !busy) { Text(stringResource(R.string.download_library)) }
                }
            },
        )
    }) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(cardWidth),
            modifier = Modifier.fillMaxSize().padding(padding).testTag("browse-content"),
            state = scroll,
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (busy) item(key = "status", span = { GridItemSpan(maxLineSpan) }) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (failed) {
                item(key = "failure", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Text(stringResource(R.string.browse_request_failed))
                        if (webSearchFallback != null && selectedSite.id == "printables" && query.isNotBlank()) {
                            TextButton(onClick = {
                                keyboard?.hide()
                                onBrowseSite(selectedSite, selectedSite.searchUrl(query))
                            }) {
                                Text(stringResource(webSearchFallback))
                            }
                        }
                        TextButton(onClick = {
                            openExternalBrowser(context, selected?.print?.pageUrl ?: selectedSite.startUrl)
                        }) {
                            Text(stringResource(R.string.browse_open_website))
                        }
                    }
                }
            }
            if (selected == null) {
                item(key = "sites", span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(siteScroll),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ModelSites.all.forEach { site ->
                            FilterChip(
                                selected = selectedSiteId == site.id,
                                enabled = !busy,
                                onClick = {
                                    selectedSiteId = site.id
                                    printablesState.results = null
                                    printablesState.failed = false
                                },
                                label = { Text(site.name) },
                            )
                        }
                    }
                }
                item(key = "query", span = { GridItemSpan(maxLineSpan) }) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.browse_search_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                    )
                }
                item(key = "submit", span = { GridItemSpan(maxLineSpan) }) {
                    Box {
                        Button(onClick = {
                            keyboard?.hide()
                            printablesState.results = null
                            if (selectedSite.id == "printables" && query.isNotBlank()) {
                                searchRevision++
                                request { printablesState.results = client.search(query) }
                            } else {
                                onBrowseSite(selectedSite, if (query.isBlank()) selectedSite.startUrl else selectedSite.searchUrl(query))
                            }
                        }, enabled = !busy) {
                            Text(stringResource(if (query.isBlank()) R.string.browse_visit_site else R.string.browse_search))
                        }
                    }
                }
                if (printablesState.results?.isEmpty() == true) {
                    item(key = "no-results", span = { GridItemSpan(maxLineSpan) }) {
                        Text(stringResource(R.string.browse_no_results))
                    }
                }
                items(printablesState.results.orEmpty(), key = { "print-${it.id}" }) { model ->
                    PrintablesModelCard(
                        model = model,
                        source = selectedSite.name,
                        thumbnails = printablesState.thumbnails,
                        enabled = !busy,
                        onClick = { request { printablesState.detail = client.getPrint(model.id) } },
                    )
                }
            } else {
                // The inherited engine advertises other formats, but only these paths are
                // covered by the initial download/import work.
                val files = selected.files.filter {
                    it.name.endsWith(".3mf", true) || it.name.endsWith(".stl", true)
                }
                item(key = "detail", span = { GridItemSpan(maxLineSpan) }) {
                    PrintablesModelHeading(selected.print, selectedSite.name, printablesState.thumbnails)
                }
                item(key = "website", span = { GridItemSpan(maxLineSpan) }) {
                    Box {
                        TextButton(onClick = { openExternalBrowser(context, selected.print.pageUrl) }) {
                            Text(stringResource(R.string.browse_open_website))
                        }
                    }
                }
                if (files.isEmpty()) item(key = "no-files", span = { GridItemSpan(maxLineSpan) }) {
                    Text(stringResource(R.string.browse_no_files))
                }
                items(files, key = { "file-${it.type}:${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { file ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(file.name)
                            Button(onClick = {
                                if (onDownloadRequested != null) {
                                    onDownloadRequested(selected.print, file)
                                } else if (onDownload != null) {
                                    request {
                                        val link = client.getDownloadLink(selected.print.id, file)
                                        onDownload(selected.print, file, link.url)
                                    }
                                }
                            }, enabled = !busy && downloadsEnabled) { Text(stringResource(R.string.browse_download_open)) }
                        }
                    }
                }
            }
        }
    }
}
