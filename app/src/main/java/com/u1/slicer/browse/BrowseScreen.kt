package com.u1.slicer.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
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

/** A native Printables entry point; existing MakerWorld import remains available. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    client: PrintablesClient,
    onDownload: suspend (PrintablesPrint, PrintablesFile, String) -> Unit,
    onBrowseMakerWorld: () -> Unit,
    onLibrary: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PrintablesPrint>?>(null) }
    var detail by remember { mutableStateOf<PrintablesPrintDetail?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    fun request(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        failed = false
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                failed = true
            } catch (_: IllegalArgumentException) {
                failed = true
            } finally {
                busy = false
            }
        }
    }

    fun back() {
        if (detail != null && !busy) {
            detail = null
            failed = false
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
            actions = { TextButton(onClick = onLibrary, enabled = !busy) { Text(stringResource(R.string.download_library)) } },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (failed) {
                Text(stringResource(R.string.browse_request_failed))
                TextButton(onClick = { uriHandler.openUri(detail?.print?.pageUrl ?: "https://www.printables.com/") }) {
                    Text(stringResource(R.string.browse_open_website))
                }
            }
            val selected = detail
            if (selected == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Printables")
                    TextButton(onClick = onBrowseMakerWorld, enabled = !busy) { Text("MakerWorld") }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.browse_search_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                )
                Button(onClick = {
                    results = null
                    request { results = client.search(query) }
                }, enabled = !busy && query.isNotBlank()) {
                    Text(stringResource(R.string.browse_search))
                }
                if (results?.isEmpty() == true) Text(stringResource(R.string.browse_no_results))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(results.orEmpty(), key = { it.id }) { model ->
                        Card(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                            request { detail = client.getPrint(model.id) }
                        }) {
                            Column(Modifier.padding(12.dp)) {
                                Text(model.name)
                                model.author?.let { Text(it) }
                            }
                        }
                    }
                }
            } else {
                Text(selected.print.name)
                TextButton(onClick = { uriHandler.openUri(selected.print.pageUrl) }) {
                    Text(stringResource(R.string.browse_open_website))
                }
                // The inherited engine advertises other formats, but only these paths are
                // covered by the initial download/import work.
                val files = selected.files.filter {
                    it.name.endsWith(".3mf", true) || it.name.endsWith(".stl", true)
                }
                if (files.isEmpty()) Text(stringResource(R.string.browse_no_files))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(files, key = { "${it.type}:${it.id}" }) { file ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(file.name)
                                Button(onClick = {
                                    request {
                                        val link = client.getDownloadLink(selected.print.id, file)
                                        onDownload(selected.print, file, link.url)
                                    }
                                }, enabled = !busy) { Text(stringResource(R.string.browse_download_open)) }
                            }
                        }
                    }
                }
            }
        }
    }
}
