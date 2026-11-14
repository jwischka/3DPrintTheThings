package com.u1.slicer.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.u1.slicer.R
import com.u1.slicer.browse.openExternalBrowser
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    store: DownloadStore,
    onOpen: (DownloadEntry) -> Unit,
    onBack: () -> Unit,
    onRetryFromSource: ((String) -> Unit)? = null,
) {
    var entries by remember { mutableStateOf<List<DownloadEntry>>(emptyList()) }
    var recoverable by remember { mutableStateOf<List<RecoverableDownload>>(emptyList()) }
    var deletion by remember { mutableStateOf<DownloadEntry?>(null) }
    var recoveryDeletion by remember { mutableStateOf<RecoverableDownload?>(null) }
    var failed by remember { mutableStateOf(false) }
    var damagedEntries by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var query by rememberSaveable { mutableStateOf("") }
    var sortName by rememberSaveable { mutableStateOf(LibrarySort.NEWEST.name) }
    var recoveryUrl by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val thumbnailCache = remember(store, context.cacheDir) { LibraryThumbnailCache(context.cacheDir, store) }
    val selectedSort = LibrarySort.values().firstOrNull { it.name == sortName } ?: LibrarySort.NEWEST
    val visibleEntries = libraryEntries(entries, query, selectedSort)
    val visibleRecoverable = recoverableEntries(recoverable, query, selectedSort)

    fun retryFromSource(pageUrl: String) {
        recoveryUrl = null
        if (onRetryFromSource != null) onRetryFromSource(pageUrl)
        else if (!openExternalBrowser(context, pageUrl)) failed = true
    }
    suspend fun refresh() {
        val listing = store.listing()
        entries = listing.entries
        recoverable = store.recoverableDownloads()
        damagedEntries = listing.unreadableEntries > 0
    }
    var resumeGeneration by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeGeneration++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(store, resumeGeneration) {
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
    recoveryDeletion?.let { item ->
        AlertDialog(
            onDismissRequest = { recoveryDeletion = null },
            title = { Text(stringResource(R.string.library_delete_title)) },
            text = { Text(stringResource(R.string.library_delete_description, item.fileName)) },
            confirmButton = {
                TextButton(onClick = {
                    recoveryDeletion = null
                    scope.launch {
                        try { failed = !store.forgetRecoverable(item); refresh() }
                        catch (_: IOException) { failed = true }
                    }
                }) { Text(stringResource(R.string.library_delete)) }
            },
            dismissButton = { TextButton(onClick = { recoveryDeletion = null }) { Text(stringResource(R.string.library_cancel)) } },
        )
    }
    recoveryUrl?.let { pageUrl ->
        AlertDialog(
            onDismissRequest = { recoveryUrl = null },
            title = { Text(stringResource(R.string.library_recovery_title)) },
            text = { Text(stringResource(R.string.library_recovery_description)) },
            confirmButton = {
                if (pageUrl.isNotBlank()) {
                    TextButton(onClick = { retryFromSource(pageUrl) }) {
                        Text(stringResource(R.string.library_retry_source))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { recoveryUrl = null }) { Text(stringResource(R.string.library_cancel)) }
            },
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
            if (entries.isNotEmpty() || recoverable.isNotEmpty()) item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.library_search_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (entries.isNotEmpty() || recoverable.isNotEmpty()) item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (option in LibrarySort.values()) {
                        val label = when (option) {
                            LibrarySort.NEWEST -> R.string.library_sort_newest
                            LibrarySort.NAME -> R.string.library_sort_name
                            LibrarySort.SITE -> R.string.library_sort_site
                            LibrarySort.SIZE -> R.string.library_sort_size
                        }
                        FilterChip(selected = sortName == option.name, onClick = { sortName = option.name },
                            label = { Text(stringResource(label)) })
                    }
                }
            }
            if (failed) item { Text(stringResource(R.string.library_failed)) }
            if (damagedEntries) item { Text(stringResource(R.string.library_damaged_entries)) }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (entries.isEmpty() && recoverable.isEmpty() && !failed && !loading) item { Text(stringResource(R.string.library_empty)) }
            if ((entries.isNotEmpty() || recoverable.isNotEmpty()) && visibleEntries.isEmpty() && visibleRecoverable.isEmpty()) {
                item { Text(stringResource(R.string.library_no_matches)) }
            }
            items(visibleEntries, key = { it.sha256 }) { entry ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LibraryThumbnail(thumbnailCache, entry)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(entry.provenance.title)
                                Text(entry.fileName)
                                Text(entry.provenance.site)
                            }
                        }
                        Row {
                            TextButton(onClick = {
                                try {
                                    val file = store.fileFor(entry)
                                    if (file.isFile && file.length() == entry.sizeBytes) onOpen(entry)
                                    else recoveryUrl = entry.provenance.pageUrl
                                } catch (_: IOException) { recoveryUrl = entry.provenance.pageUrl }
                            }) { Text(stringResource(R.string.library_open)) }
                            if (entry.provenance.pageUrl.isNotBlank()) {
                                TextButton(onClick = { if (!openExternalBrowser(context, entry.provenance.pageUrl)) failed = true }) {
                                    Text(stringResource(R.string.browse_open_website))
                                }
                            }
                            TextButton(onClick = { deletion = entry }) { Text(stringResource(R.string.library_delete)) }
                        }
                        if (entry.provenance.pageUrl.isNotBlank()) {
                            TextButton(onClick = { retryFromSource(entry.provenance.pageUrl) }) {
                                Text(stringResource(R.string.library_retry_source))
                            }
                        }
                    }
                }
            }
            items(visibleRecoverable, key = { "recoverable-${it.sha256}" }) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(item.provenance.title)
                        Text(item.fileName)
                        Text(stringResource(R.string.library_recovery_description))
                        Row {
                            if (item.provenance.pageUrl.isNotBlank()) {
                                TextButton(onClick = { retryFromSource(item.provenance.pageUrl) }) {
                                    Text(stringResource(R.string.library_retry_source))
                                }
                            }
                            TextButton(onClick = { recoveryDeletion = item }) {
                                Text(stringResource(R.string.library_delete))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryThumbnail(cache: LibraryThumbnailCache, entry: DownloadEntry) {
    val bitmap by produceState<Bitmap?>(initialValue = null, cache, entry.sha256) {
        val image = cache.thumbnailFile(entry)
        value = if (image != null) withContext(Dispatchers.IO) { decodeBoundedThumbnail(image) } else null
    }
    Box(
        modifier = Modifier.size(72.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap!!.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(72.dp))
        } else {
            Text(entry.fileName.substringAfterLast('.', "model").uppercase(Locale.ROOT),
                style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun decodeBoundedThumbnail(file: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth !in 1..2048 || bounds.outHeight !in 1..2048) return null
    var sample = 1
    while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
    return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
}
