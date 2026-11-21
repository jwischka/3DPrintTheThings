package com.u1.slicer.library

import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(
    store: DownloadStore,
    hash: String,
    onOpen: ((File, String) -> Unit)? = null,
    onBack: () -> Unit,
    openEnabled: Boolean = true,
    onCandidateSelected: ((DownloadEntry, ModelArchive.Candidate) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val archiveReader = remember { ModelArchive() }
    var archive by remember(hash) { mutableStateOf<DownloadEntry?>(null) }
    var candidates by remember(hash) { mutableStateOf<List<ModelArchive.Candidate>>(emptyList()) }
    var busy by remember(hash) { mutableStateOf(true) }
    var failed by remember(hash) { mutableStateOf(false) }
    LaunchedEffect(hash) {
        try {
            val entry = store.entry(hash) ?: throw IOException("Archive missing")
            archive = entry
            candidates = archiveReader.list(store.fileFor(entry))
        } catch (_: IOException) { failed = true }
        finally { busy = false }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.archive_choose_model)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.browse_back)) }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (failed) item { Text(stringResource(R.string.archive_import_failed)) }
            if (!busy && !failed && candidates.isEmpty()) item { Text(stringResource(R.string.archive_no_models)) }
            items(candidates, key = { it.index }) { candidate ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(candidate.path)
                        Text(Formatter.formatShortFileSize(context, candidate.sizeBytes))
                        TextButton(enabled = !busy && openEnabled, onClick = {
                            val entry = archive ?: return@TextButton
                            if (onCandidateSelected != null) {
                                onCandidateSelected(entry, candidate)
                                return@TextButton
                            }
                            val open = onOpen ?: return@TextButton
                            busy = true
                            failed = false
                            scope.launch {
                                val temporary = File(context.cacheDir, "archive-${UUID.randomUUID()}.model")
                                try {
                                    archiveReader.extract(store.fileFor(entry), candidate, temporary)
                                    val imported = store.importFile(temporary, candidate.path.substringAfterLast('/'), entry.provenance)
                                    open(store.fileFor(imported), imported.fileName)
                                } catch (_: IOException) { failed = true }
                                catch (_: IllegalArgumentException) { failed = true }
                                finally {
                                    temporary.delete()
                                    busy = false
                                }
                            }
                        }) { Text(stringResource(R.string.library_open)) }
                    }
                }
            }
        }
    }
}

/** Copies a granted content URI before opening the durable archive picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncomingArchiveScreen(
    uri: String, fileName: String, store: DownloadStore,
    onImported: (DownloadEntry) -> Unit, onBack: () -> Unit,
) {
    val context = LocalContext.current
    var failed by remember(uri) { mutableStateOf(false) }
    LaunchedEffect(uri) {
        val temporary = File(context.cacheDir, "shared-${UUID.randomUUID()}.zip")
        try {
            val source = Uri.parse(uri)
            if (source.scheme != "content") throw IOException("A granted content URI is required")
            withContext(Dispatchers.IO) {
                val coroutineContext = currentCoroutineContext()
                val input = context.contentResolver.openInputStream(source) ?: throw IOException("Cannot open shared archive")
                input.use {
                    temporary.outputStream().use { output ->
                        var total = 0L
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > 256L * 1024 * 1024) throw IOException("Shared archive exceeds size limit")
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            val name = fileName.takeIf { it.endsWith(".zip", true) } ?: "archive.zip"
            onImported(store.importFile(temporary, name, DownloadProvenance("local", "", name)))
        } catch (_: IOException) { failed = true }
        catch (_: SecurityException) { failed = true }
        catch (_: IllegalArgumentException) { failed = true }
        finally { temporary.delete() }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.archive_choose_model)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.browse_back)) }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            if (failed) Text(stringResource(R.string.archive_import_failed))
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
