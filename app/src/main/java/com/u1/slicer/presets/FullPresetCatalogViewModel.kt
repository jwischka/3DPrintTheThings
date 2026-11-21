package com.u1.slicer.presets

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.u1.slicer.R
import com.u1.slicer.browse.BrowserDownload
import com.u1.slicer.browse.BrowsePrintablesState
import com.u1.slicer.browse.ModelSite
import com.u1.slicer.browse.printables.PrintablesClient
import com.u1.slicer.browse.printables.PrintablesFile
import com.u1.slicer.browse.printables.PrintablesPrint
import com.u1.slicer.library.DownloadEntry
import com.u1.slicer.library.DownloadProvenance
import com.u1.slicer.library.DownloadStore
import com.u1.slicer.library.ModelArchive
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val TRANSFER_TIMEOUT_MINUTES = 5L

private fun defaultCatalogHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .callTimeout(TRANSFER_TIMEOUT_MINUTES, TimeUnit.MINUTES)
    .build()

internal sealed interface FullPresetCatalogTransfer {
    data object Idle : FullPresetCatalogTransfer
    data class InProgress(
        val fileName: String,
        @StringRes val label: Int,
        val cancelling: Boolean = false,
    ) : FullPresetCatalogTransfer
    data class Ready(
        val entry: DownloadEntry,
        val sourceUrl: String?,
        val origin: Long?,
    ) : FullPresetCatalogTransfer
    data class Failed(@StringRes val message: Int) : FullPresetCatalogTransfer
}

internal data class FullPresetCatalogState(
    val transfer: FullPresetCatalogTransfer = FullPresetCatalogTransfer.Idle,
    val libraryRevision: Int = 0,
)

/** Owns downloads and extraction independently of the Compose screen or native workspace. */
internal class FullPresetCatalogViewModel @JvmOverloads constructor(
    application: Application,
    httpClient: OkHttpClient = defaultCatalogHttpClient(),
    val printablesClient: PrintablesClient = PrintablesClient(httpClient),
    filesDir: File = application.filesDir,
) : AndroidViewModel(application) {
    val store = DownloadStore(filesDir, httpClient)
    val browseState = BrowsePrintablesState()
    private val archiveReader = ModelArchive()
    private val mutableState = MutableStateFlow(FullPresetCatalogState())
    val state = mutableState.asStateFlow()

    // The transfer and its state are owned by the main thread.
    private var transferJob: Job? = null

    fun downloadPrintables(model: PrintablesPrint, file: PrintablesFile, origin: Long) {
        startTransfer(file.name, model.pageUrl, origin) {
            val link = printablesClient.getDownloadLink(model.id, file)
            store.download(
                link.url,
                file.name,
                DownloadProvenance(
                    site = "printables",
                    pageUrl = model.pageUrl,
                    title = model.name,
                    author = model.author,
                    thumbnailUrl = model.imageUrl,
                ),
            )
        }
    }

    fun downloadFromBrowser(site: ModelSite, download: BrowserDownload, origin: Long) {
        if (transferJob != null || !site.ownsPage(download.pageUrl)) return
        val provenance = DownloadProvenance(site.id, download.pageUrl, download.title)
        when (download) {
            is BrowserDownload.Network -> startTransfer(download.fileName, download.pageUrl, origin) {
                store.download(
                    download.url,
                    download.fileName,
                    provenance,
                    headers = buildMap {
                        put("User-Agent", download.userAgent)
                        download.cookie?.takeIf(String::isNotBlank)?.let { put("Cookie", it) }
                    },
                )
            }
            is BrowserDownload.Local -> {
                // The browser deletes its temporary path when the callback returns. Move the
                // completed blob within that cache directory before giving it to the retained job.
                val ownedFile = File(download.tempFile.parentFile, "catalog-blob-${UUID.randomUUID()}.partial")
                if (!download.tempFile.renameTo(ownedFile)) {
                    showFailure(R.string.full_presets_catalog_download_failed)
                    return
                }
                startTransfer(download.fileName, download.pageUrl, origin, localBlob = ownedFile) {
                    store.importFile(ownedFile, download.fileName, provenance)
                }
            }
        }
    }

    fun extract(entry: DownloadEntry, candidate: ModelArchive.Candidate, sourceUrl: String?, origin: Long) {
        startTransfer(
            fileName = candidate.path.substringAfterLast('/'),
            sourceUrl = sourceUrl,
            origin = origin,
            label = R.string.full_presets_catalog_extracting,
            failure = R.string.archive_import_failed,
        ) {
            withContext(Dispatchers.IO) {
                val temporary = File(getApplication<Application>().cacheDir, "catalog-archive-${UUID.randomUUID()}.partial")
                try {
                    archiveReader.extract(store.fileFor(entry), candidate, temporary)
                    store.importFile(temporary, candidate.path.substringAfterLast('/'), entry.provenance)
                } finally {
                    temporary.delete()
                }
            }
        }
    }

    fun cancelTransfer() {
        val progress = mutableState.value.transfer as? FullPresetCatalogTransfer.InProgress ?: return
        setTransfer(progress.copy(cancelling = true))
        transferJob?.cancel()
    }

    fun dismissTransfer() {
        if (transferJob == null) setTransfer(FullPresetCatalogTransfer.Idle)
    }

    fun requireExplicitOpen(ready: FullPresetCatalogTransfer.Ready) {
        if (mutableState.value.transfer == ready) setTransfer(ready.copy(origin = null))
    }

    fun showFailure(@StringRes message: Int) {
        if (transferJob == null) setTransfer(FullPresetCatalogTransfer.Failed(message))
    }

    private fun startTransfer(
        fileName: String,
        sourceUrl: String?,
        origin: Long,
        @StringRes label: Int = R.string.full_presets_catalog_downloading,
        @StringRes failure: Int = R.string.full_presets_catalog_download_failed,
        localBlob: File? = null,
        transfer: suspend () -> DownloadEntry,
    ) {
        if (transferJob != null) return
        setTransfer(FullPresetCatalogTransfer.InProgress(fileName, label))
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val entry = withTimeout(TimeUnit.MINUTES.toMillis(TRANSFER_TIMEOUT_MINUTES)) { transfer() }
                val publicUrl = sourceUrl?.toHttpUrlOrNull()?.takeIf {
                    it.username.isEmpty() && it.password.isEmpty()
                }?.newBuilder()?.query(null)?.fragment(null)?.build()?.toString()
                mutableState.value = FullPresetCatalogState(
                    transfer = FullPresetCatalogTransfer.Ready(entry, publicUrl, origin),
                    libraryRevision = mutableState.value.libraryRevision + 1,
                )
            } catch (_: TimeoutCancellationException) {
                setTransfer(FullPresetCatalogTransfer.Failed(R.string.full_presets_catalog_timeout))
            } catch (_: IOException) {
                setTransfer(FullPresetCatalogTransfer.Failed(failure))
            } catch (_: IllegalArgumentException) {
                setTransfer(FullPresetCatalogTransfer.Failed(failure))
            } finally {
                transferJob = null
                if (mutableState.value.transfer is FullPresetCatalogTransfer.InProgress) {
                    setTransfer(FullPresetCatalogTransfer.Idle)
                }
            }
        }
        transferJob = job
        if (localBlob != null) job.invokeOnCompletion { localBlob.delete() }
        job.start()
    }

    private fun setTransfer(transfer: FullPresetCatalogTransfer) {
        mutableState.value = mutableState.value.copy(transfer = transfer)
    }
}
