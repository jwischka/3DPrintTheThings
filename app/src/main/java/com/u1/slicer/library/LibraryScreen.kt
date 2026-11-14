package com.u1.slicer.library

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.u1.slicer.R
import com.u1.slicer.browse.openExternalBrowser
import kotlinx.coroutines.launch
import java.io.IOException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(store: DownloadStore, onOpen: (DownloadEntry) -> Unit, onBack: () -> Unit) {
    var entries by remember { mutableStateOf<List<DownloadEntry>>(emptyList()) }
    var deletion by remember { mutableStateOf<DownloadEntry?>(null) }
    var failed by remember { mutableStateOf(false) }
    var damagedEntries by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    suspend fun refresh() {
        val listing = store.listing()
        entries = listing.entries
        damagedEntries = listing.unreadableEntries > 0
    }
    LaunchedEffect(store) {
        try { refresh() } catch (_: IOException) { failed = true }
        finally { loading = false }
    }
    deletion?.let { entry ->
        AlertDialog(
            onDismissRequest = { deletion = null },
            title = { Text(stringResource(R.string.library_delete_title)) },
            text = { Text(stringResource(R.string.library_delete_description, entry.fileName)) },
            confirmButton = {
                TextButton(onClick = {
                    deletion = null
                    scope.launch {
                        try {
                            failed = !store.delete(entry)
                            refresh()
                        } catch (_: IOException) { failed = true }
                    }
                }) { Text(stringResource(R.string.library_delete)) }
            },
            dismissButton = { TextButton(onClick = { deletion = null }) { Text(stringResource(R.string.library_cancel)) } },
        )
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.download_library)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.browse_back)) }
        })
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (failed) item { Text(stringResource(R.string.library_failed)) }
            if (damagedEntries) item { Text(stringResource(R.string.library_damaged_entries)) }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (entries.isEmpty() && !failed && !loading) item { Text(stringResource(R.string.library_empty)) }
            items(entries, key = { it.sha256 }) { entry ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(entry.provenance.title)
                        Text(entry.fileName)
                        Row {
                            TextButton(onClick = {
                                try {
                                    val file = store.fileFor(entry)
                                    if (file.isFile) onOpen(entry) else failed = true
                                } catch (_: IOException) { failed = true }
                            }) { Text(stringResource(R.string.library_open)) }
                            if (entry.provenance.pageUrl.isNotBlank()) {
                                TextButton(onClick = { if (!openExternalBrowser(context, entry.provenance.pageUrl)) failed = true }) {
                                    Text(stringResource(R.string.browse_open_website))
                                }
                            }
                            TextButton(onClick = { deletion = entry }) { Text(stringResource(R.string.library_delete)) }
                        }
                    }
                }
            }
        }
    }
}
