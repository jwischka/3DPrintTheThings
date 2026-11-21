package com.u1.slicer.presets

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.u1.slicer.NativeLibrary
import com.u1.slicer.LongOpService
import com.u1.slicer.R
import com.u1.slicer.data.ModelFileNaming
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.data.SliceResult
import com.u1.slicer.library.publicDownloadUrl
import com.u1.slicer.network.U1FilamentResult
import com.u1.slicer.presets.preview.FullPresetPreview
import com.u1.slicer.presets.preview.FullPresetPreviewLoader
import com.u1.slicer.ui.printer.experimental.ExperimentalPrinterConnection
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val MAX_ERROR_LENGTH = 500

internal data class FullPresetSelection(
    val printer: String,
    val process: String,
    val filaments: List<String>,
) {
    constructor(bundle: FullPresetStore.Bundle) : this(
        bundle.printer.path,
        bundle.process.path,
        bundle.filaments.map { it.path },
    )
}

internal data class FullPresetModel(
    val name: String,
    val file: File,
    val sourceUrl: String? = null,
    val filaments: FullPresetModelFilaments? = null,
)

internal data class FullPresetOutput(
    val filename: String,
    val result: SliceResult,
    val mapping: FullPresetAppliedFilamentMapping? = null,
    val presetNames: List<String> = emptyList(),
)

internal enum class FullPresetOperation(@StringRes val label: Int) {
    READING_BUNDLES(R.string.full_presets_reading_bundles),
    IMPORTING_BUNDLE(R.string.full_presets_importing_bundle),
    IMPORTING_MODEL(R.string.full_presets_importing_model),
    SLICING(R.string.full_presets_slicing),
    CANCELLING(R.string.full_presets_cancelling),
    EXPORTING(R.string.full_presets_exporting),
    PRINTING(R.string.full_presets_print_sending),
}

internal data class FullPresetNotice(@StringRes val message: Int, val detail: String? = null)

internal data class FullPresetUiState(
    val bundles: List<FullPresetStore.Bundle> = emptyList(),
    val bundleId: String? = null,
    val selection: FullPresetSelection? = null,
    val model: FullPresetModel? = null,
    val preview: FullPresetPreview = FullPresetPreview.Empty,
    val printerFilaments: FullPresetPrinterFilaments = FullPresetPrinterFilaments.Unchecked,
    val refreshingPrinterFilaments: Boolean = false,
    val filamentMapping: FullPresetAppliedFilamentMapping? = null,
    val output: FullPresetOutput? = null,
    val printReview: FullPresetPrintReview? = null,
    val operation: FullPresetOperation? = null,
    val progress: Int? = null,
    val error: FullPresetNotice? = null,
    @StringRes val status: Int? = null,
) {
    val bundle get() = bundles.find { it.id == bundleId }
}

internal class FullPresetViewModel(application: Application) : AndroidViewModel(application) {
    private val resolver = application.contentResolver
    private val store by lazy { FullPresetStore(File(application.noBackupFilesDir, "full-presets")) }
    private val printerConnection by lazy { ExperimentalPrinterConnection(application) }
    private val sessionDir by lazy { File(application.cacheDir, "full-presets/${UUID.randomUUID()}") }
    private val mutableState = MutableStateFlow(FullPresetUiState())
    val state = mutableState.asStateFlow()

    // Mutations and operation ownership are on the main thread. Native progress only updates StateFlow.
    private var operationJob: Job? = null
    private var sliceCancellation: SliceCancellation? = null
    private var printReviewJob: Job? = null
    private var terminalPrintReview: FullPresetPrintReview? = null
    private val printService by lazy { FullPresetPrintService(printerConnection) }

    init {
        runOperation(FullPresetOperation.READING_BUNDLES, R.string.full_presets_read_bundles_failed) {
            val bundles = withContext(Dispatchers.IO) { store.list() }
            val bundle = bundles.firstOrNull()
            mutableState.update {
                it.copy(bundles = bundles, bundleId = bundle?.id, selection = bundle?.let(::FullPresetSelection))
            }
        }
    }

    fun importBundle(uri: Uri) {
        runOperation(FullPresetOperation.IMPORTING_BUNDLE, R.string.full_presets_bundle_import_failed) {
            val (imported, bundles) = withContext(Dispatchers.IO) {
                val bundle = resolver.openInputStream(uri)?.let(store::importZip)
                    ?: throw WorkflowFailure(R.string.full_presets_document_read_failed)
                bundle to store.list()
            }
            mutableState.update {
                it.copy(bundles = bundles, bundleId = imported.id, selection = FullPresetSelection(imported), filamentMapping = null)
            }
        }
    }

    fun selectBundle(id: String) {
        val snapshot = state.value
        if (snapshot.operation != null || snapshot.bundleId == id) return
        val bundle = snapshot.bundles.find { it.id == id } ?: return
        mutableState.value = snapshot.copy(
            bundleId = id,
            selection = FullPresetSelection(bundle),
            filamentMapping = null,
            output = null,
            printReview = null,
            error = null,
            status = null,
        )
    }

    fun selectPrinter(path: String) = updateSelection { it.copy(printer = path) }

    fun selectProcess(path: String) = updateSelection { it.copy(process = path) }

    fun selectFilament(index: Int, path: String) = updateSelection { selection ->
        selection.copy(filaments = selection.filaments.mapIndexed { i, previous ->
            if (i == index) path else previous
        })
    }

    fun addFilament() = updateSelection { selection ->
        if (selection.filaments.size >= MAX_FILAMENTS) selection
        else selection.copy(filaments = selection.filaments + selection.filaments.last())
    }

    fun removeLastFilament() = updateSelection { selection ->
        if (selection.filaments.size <= 1) selection
        else selection.copy(filaments = selection.filaments.dropLast(1))
    }

    private fun updateSelection(transform: (FullPresetSelection) -> FullPresetSelection) {
        val snapshot = state.value
        val selection = snapshot.selection ?: return
        if (snapshot.operation != null) return
        val updated = transform(selection)
        if (updated != selection) {
            mutableState.value = snapshot.copy(selection = updated, filamentMapping = null, output = null, printReview = null, error = null, status = null)
        }
    }

    fun refreshPrinterFilaments() {
        val snapshot = state.value
        if (snapshot.operation != null || snapshot.refreshingPrinterFilaments) return
        mutableState.value = snapshot.copy(refreshingPrinterFilaments = true)
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) { readPrinterFilaments() }
            mutableState.update { state ->
                if (state.printerFilaments != snapshot.printerFilaments) {
                    return@update state.copy(refreshingPrinterFilaments = false)
                }
                val displayed = if (report is FullPresetPrinterFilaments.Unavailable) {
                    report.copy(lastReady = state.printerFilaments.displayedReport)
                } else report
                state.copy(printerFilaments = displayed, refreshingPrinterFilaments = false)
            }
        }
    }

    fun applyPrinterFilamentMapping(targets: Map<Int, Int>, physicalPresets: List<String>) {
        val before = state.value
        if (before.operation != null || before.refreshingPrinterFilaments ||
            physicalPresets.size != fullPresetPhysicalSlots.count()) return
        val manifestMapping = before.model?.filaments?.positionalMapping(targets) ?: return
        val reported = before.printerFilaments as? FullPresetPrinterFilaments.Ready ?: return
        if (!reported.hasLoadedTargets(targets.values)) return
        val availablePresets = before.bundle?.catalog?.filter { it.isSelectable && it.category == FullPresetStore.Category.FILAMENT }
            ?.map(FullPresetStore.Preset::path).orEmpty()
        if (physicalPresets.any { it !in availablePresets }) return
        val mapping = FullPresetAppliedFilamentMapping(targets.toMap(), manifestMapping, physicalPresets.toList(), reported)
        viewModelScope.launch {
            val current = withContext(Dispatchers.IO) { printerConnection.isCurrent(reported.connection) }
            mutableState.update { state ->
                if (state.operation != null || state.refreshingPrinterFilaments || state.model != before.model || state.bundleId != before.bundleId ||
                    state.selection != before.selection || state.printerFilaments != reported || state.filamentMapping != before.filamentMapping
                ) state
                else if (!current || !reported.snapshot.isFresh(SystemClock.elapsedRealtime())) {
                    state.copy(printerFilaments = FullPresetPrinterFilaments.Unavailable(reported))
                } else {
                    state.copy(filamentMapping = mapping, output = null, printReview = null, error = null, status = null)
                }
            }
        }
    }

    fun useManualFilaments() {
        mutableState.update { state ->
            if (state.operation != null || state.filamentMapping == null) state
            else state.copy(filamentMapping = null, output = null, printReview = null, error = null, status = null)
        }
    }

    private suspend fun readPrinterFilaments(): FullPresetPrinterFilaments = try {
        val connection = printerConnection.load()
        if (connection.endpoint.isBlank()) {
            FullPresetPrinterFilaments.NoConnection
        } else {
            val result = printerConnection.clientIfCurrent(connection)?.readU1FilamentSnapshot()
            if (result is U1FilamentResult.Received && printerConnection.isCurrent(connection)) {
                FullPresetPrinterFilaments.Ready(connection, result.snapshot)
            } else FullPresetPrinterFilaments.Unavailable()
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        FullPresetPrinterFilaments.Unavailable()
    }

    fun importModel(uri: Uri) {
        importModel { copyModel(uri) }
    }

    /** Copy a Library file into the Prepare session. Returns false while another operation is active. */
    @MainThread
    fun importModelFile(file: File, displayName: String, sourceUrl: String? = null): Boolean =
        importModel {
            val extension = modelExtension(displayName) ?: modelExtension(file.name)
                ?: throw WorkflowFailure(R.string.full_presets_model_type)
            if (!file.isFile) throw WorkflowFailure(R.string.full_presets_document_read_failed)
            if (file.length() > MAX_MODEL_BYTES) throw WorkflowFailure(R.string.full_presets_model_too_large)
            copyModel(displayName.ifBlank { file.name }, extension, fullPresetModelSourceUrl(sourceUrl)) {
                file.inputStream()
            }
        }

    private fun importModel(copy: suspend () -> FullPresetModel): Boolean =
        runOperation(FullPresetOperation.IMPORTING_MODEL, R.string.full_presets_model_import_failed) {
            val previous = state.value
            mutableState.update { it.copy(preview = FullPresetPreview.Loading) }
            try {
                val (model, preview) = withContext(Dispatchers.IO) {
                    val imported = copy()
                    var complete = false
                    try {
                        val loaded = FullPresetPreviewLoader.load(imported.file)
                        complete = true
                        imported.copy(filaments = loaded.filaments) to loaded.preview
                    } finally {
                        if (!complete) imported.file.parentFile?.deleteRecursively()
                    }
                }
                mutableState.update { it.copy(model = model, preview = preview, filamentMapping = null) }
                withContext(Dispatchers.IO) { previous.model?.file?.parentFile?.deleteRecursively() }
            } catch (error: FullPresetPreviewLoader.InvalidModel) {
                throw WorkflowFailure(error.reason)
            } finally {
                mutableState.update {
                    if (it.preview == FullPresetPreview.Loading) it.copy(preview = previous.preview) else it
                }
            }
        }

    fun slice() {
        val snapshot = state.value
        val bundle = snapshot.bundle ?: return
        val selection = snapshot.selection ?: return
        val model = snapshot.model ?: return
        val mapping = snapshot.filamentMapping
        runOperation(FullPresetOperation.SLICING, R.string.full_presets_slice_failed) {
            val cancellation = SliceCancellation()
            sliceCancellation = cancellation
            try {
                val result = withContext(Dispatchers.IO) {
                    val fresh = if (mapping == null) null else {
                        val report = readPrinterFilaments()
                        mutableState.update { state ->
                            val displayed = if (report is FullPresetPrinterFilaments.Unavailable) {
                                report.copy(lastReady = state.printerFilaments.displayedReport)
                            } else report
                            state.copy(printerFilaments = displayed)
                        }
                        if (cancellation.requested) return@withContext null
                        if (report !is FullPresetPrinterFilaments.Ready || !mapping.matches(report)) {
                            throw WorkflowFailure(R.string.full_presets_mapping_recheck)
                        }
                        report
                    }
                    // Competing activity instances can select the same bundle. Hold the lock from writing
                    // the shared manifest through loading it, so each slice uses its own selection.
                    NativeLibrary.previewMutex.withLock {
                        if (cancellation.requested) return@withLock null
                        if (fresh != null && (!fresh.snapshot.isFresh(SystemClock.elapsedRealtime()) ||
                                !printerConnection.isCurrent(fresh.connection))) {
                            throw WorkflowFailure(R.string.full_presets_mapping_recheck)
                        }
                        val manifest = store.writeSelection(
                            bundle.id,
                            selection.printer,
                            selection.process,
                            mapping?.physicalPresets ?: selection.filaments,
                            mapping?.positionalMapping,
                        )
                        if (mapping == null) {
                            val catalog = bundle.catalog.associateBy { it.path }
                            val savedSelection = bundle.copy(
                                manifest = manifest,
                                printer = catalog.getValue(selection.printer),
                                process = catalog.getValue(selection.process),
                                filaments = selection.filaments.map(catalog::getValue),
                            )
                            mutableState.update {
                                it.copy(bundles = it.bundles.map { entry -> if (entry.id == bundle.id) savedSelection else entry })
                            }
                        }
                        runNativeSlice(manifest, model, cancellation)
                    }
                }
                // Cancellation can arrive after JNI finishes and before this result reaches the UI.
                if (result == null || cancellation.requested) {
                    if (result != null) withContext(Dispatchers.IO) { File(result.gcodePath).delete() }
                    mutableState.update { it.copy(status = R.string.full_presets_slice_cancelled) }
                } else {
                    val name = ModelFileNaming.baseName(model.name, model.file.name).take(128) + ".gcode"
                    val catalog = bundle.catalog.associateBy { it.path }
                    val names = (mapping?.physicalPresets ?: selection.filaments).map { catalog.getValue(it).name }
                    mutableState.update { it.copy(output = FullPresetOutput(name, result, mapping, names)) }
                }
            } finally {
                sliceCancellation = null
            }
        }
    }

    fun cancelSlice() {
        val cancellation = sliceCancellation ?: return
        if (state.value.operation != FullPresetOperation.SLICING) return
        mutableState.update { it.copy(operation = FullPresetOperation.CANCELLING, progress = null) }
        cancellation.request()
    }

    fun exportGcode(uri: Uri) {
        val output = state.value.output ?: return
        runOperation(FullPresetOperation.EXPORTING, R.string.full_presets_export_failed, invalidateOutput = false) {
            withContext(Dispatchers.IO) {
                val file = File(output.result.gcodePath)
                if (!file.isFile || file.length() == 0L) {
                    throw WorkflowFailure(R.string.full_presets_output_missing)
                }
                file.inputStream().use { source ->
                    val target = resolver.openOutputStream(uri, "wt")
                        ?: throw WorkflowFailure(R.string.full_presets_export_failed)
                    target.use { source.copyTo(it) }
                }
            }
            mutableState.update { it.copy(status = R.string.full_presets_gcode_saved) }
        }
    }

    fun openPrintReview() {
        val snapshot = state.value
        val output = snapshot.output ?: return
        if (snapshot.operation != null) return
        printReviewJob?.cancel()
        val terminal = terminalPrintReview?.takeIf { it.output === output }
        if (terminal != null) {
            mutableState.update { it.copy(printReview = terminal) }
            return
        }
        val review = FullPresetPrintReview(output)
        mutableState.update { it.copy(printReview = review) }
        val mapping = output.mapping
        if (mapping == null) {
            mutableState.update { it.copy(printReview = review.copy(
                phase = FullPresetPrintPhase.FAILED, message = R.string.full_presets_print_requires_mapping,
            )) }
            return
        }
        printReviewJob = viewModelScope.launch {
            var prepared = review
            try {
                prepared = runInterruptible(Dispatchers.IO) {
                    val file = File(output.result.gcodePath)
                    val usage = FullPresetGcodeInspection.inspect(file)
                    review.copy(
                        usage = usage,
                        job = FullPresetPrintJob(file, output.filename, mapping.confirmed,
                            usage.requiredSlots, usage.sha256, usage.bytes, usage.materialTypesBySlot),
                    )
                }
                val result = withContext(Dispatchers.IO) { printService.review(requireNotNull(prepared.job)) }
                prepared = prepared.withResult(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                prepared = prepared.copy(phase = FullPresetPrintPhase.FAILED,
                    message = R.string.full_presets_print_invalid_job)
            }
            // Closing the sheet, replacing the model, or starting another operation discards late reads.
            mutableState.update { current ->
                if (current.output === output && current.printReview === review) current.copy(printReview = prepared)
                else current
            }
        }
    }

    fun refreshPrintReview() = openPrintReview()

    fun closePrintReview() {
        if (state.value.operation == FullPresetOperation.PRINTING) return
        printReviewJob?.cancel()
        mutableState.update { it.copy(printReview = null) }
    }

    fun confirmPrint() {
        val snapshot = state.value
        val review = snapshot.printReview ?: return
        val job = review.job ?: return
        if (review.phase != FullPresetPrintPhase.READY || snapshot.output !== review.output ||
            terminalPrintReview?.output === review.output) return
        runOperation(FullPresetOperation.PRINTING, R.string.full_presets_print_failed, invalidateOutput = false) {
            val application = getApplication<Application>()
            val startRequested = AtomicBoolean(false)
            try {
                LongOpService.start(application, application.getString(R.string.full_presets_print_sending))
                val result = withContext(Dispatchers.IO) {
                    printService.send(job) { progress ->
                        val (phase, percent) = when (progress) {
                            FullPresetPrintProgress.Checking -> FullPresetPrintPhase.CHECKING to null
                            is FullPresetPrintProgress.Uploading -> FullPresetPrintPhase.UPLOADING to
                                (if (progress.totalBytes > 0) (100.0 * progress.sentBytes / progress.totalBytes).toInt().coerceIn(0, 100) else null)
                            FullPresetPrintProgress.Starting -> {
                                startRequested.set(true)
                                FullPresetPrintPhase.STARTING to null
                            }
                        }
                        mutableState.update { it.copy(printReview = review.copy(phase = phase, progressPercent = percent)) }
                    }
                }
                val completed = review.withResult(result)
                if (completed.phase in setOf(FullPresetPrintPhase.STARTED, FullPresetPrintPhase.UNCERTAIN)) {
                    terminalPrintReview = completed
                }
                mutableState.update { it.copy(printReview = completed) }
            } catch (cancelled: CancellationException) {
                if (startRequested.get()) {
                    terminalPrintReview = review.copy(phase = FullPresetPrintPhase.UNCERTAIN)
                    mutableState.update { it.copy(printReview = terminalPrintReview) }
                }
                throw cancelled
            } catch (_: Exception) {
                val failed = review.copy(
                    phase = if (startRequested.get()) FullPresetPrintPhase.UNCERTAIN else FullPresetPrintPhase.FAILED,
                    message = if (startRequested.get()) null else R.string.full_presets_print_failed,
                )
                if (startRequested.get()) terminalPrintReview = failed
                mutableState.update { it.copy(printReview = failed) }
            } finally {
                LongOpService.stop(application)
            }
        }
    }

    private fun FullPresetPrintReview.withResult(result: FullPresetPrintResult): FullPresetPrintReview = when (result) {
        is FullPresetPrintResult.Ready -> copy(phase = FullPresetPrintPhase.READY, report = result.current,
            checkedAtElapsedMillis = result.current.snapshot.receivedAtElapsedMillis, message = null)
        FullPresetPrintResult.Started -> copy(phase = FullPresetPrintPhase.STARTED, progressPercent = null)
        FullPresetPrintResult.Uncertain -> copy(phase = FullPresetPrintPhase.UNCERTAIN, progressPercent = null)
        is FullPresetPrintResult.Failed -> copy(phase = FullPresetPrintPhase.FAILED, progressPercent = null,
            message = when (result.reason) {
                FullPresetPrintFailure.INVALID_JOB -> R.string.full_presets_print_invalid_job
                FullPresetPrintFailure.OUTPUT_CHANGED -> R.string.full_presets_print_output_changed
                FullPresetPrintFailure.CONNECTION_CHANGED -> R.string.full_presets_print_connection_changed
                FullPresetPrintFailure.PRINTER_NOT_READY -> R.string.full_presets_print_printer_not_ready
                FullPresetPrintFailure.FILAMENT_REPORT_UNAVAILABLE -> R.string.full_presets_print_filament_report_unavailable
                FullPresetPrintFailure.FILAMENTS_CHANGED -> R.string.full_presets_print_filaments_changed
                FullPresetPrintFailure.MATERIAL_MISMATCH -> R.string.full_presets_print_material_mismatch
                FullPresetPrintFailure.UPLOAD_UNCONFIRMED -> R.string.full_presets_print_upload_unconfirmed
                FullPresetPrintFailure.START_REJECTED -> R.string.full_presets_print_start_rejected
                FullPresetPrintFailure.BUSY -> R.string.full_presets_print_busy
            })
    }

    private fun runOperation(
        operation: FullPresetOperation,
        @StringRes failure: Int,
        invalidateOutput: Boolean = true,
        action: suspend () -> Unit,
    ): Boolean {
        val snapshot = state.value
        if (snapshot.operation != null) return false
        mutableState.value = snapshot.copy(
            operation = operation,
            progress = null,
            output = snapshot.output.takeUnless { invalidateOutput },
            printReview = snapshot.printReview.takeUnless { invalidateOutput },
            error = null,
            status = null,
        )
        operationJob = viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: WorkflowFailure) {
                mutableState.update {
                    val output = it.output.takeUnless { error.label == R.string.full_presets_output_missing }
                    it.copy(output = output, error = FullPresetNotice(error.label, error.detail))
                }
            } catch (error: LinkageError) {
                mutableState.update { it.copy(error = FullPresetNotice(R.string.full_presets_engine_unavailable)) }
            } catch (error: Exception) {
                mutableState.update {
                    val detail = (error as? FullPresetStore.InvalidBundle)?.message
                    it.copy(error = FullPresetNotice(failure, detail?.take(MAX_ERROR_LENGTH)))
                }
            } finally {
                mutableState.update { it.copy(operation = null, progress = null) }
            }
        }
        return true
    }

    private suspend fun copyModel(uri: Uri): FullPresetModel {
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        val extension = modelExtension(displayName) ?: when (resolver.getType(uri)) {
            "model/stl", "application/sla", "application/vnd.ms-pki.stl" -> "stl"
            "model/3mf", "application/vnd.ms-3mfdocument" -> "3mf"
            else -> throw WorkflowFailure(R.string.full_presets_model_type)
        }
        return copyModel(displayName, extension) { resolver.openInputStream(uri) }
    }

    private fun modelExtension(name: String?): String? =
        name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)?.takeIf { it == "stl" || it == "3mf" }

    private suspend fun copyModel(
        displayName: String?,
        extension: String,
        sourceUrl: String? = null,
        openInput: () -> InputStream?,
    ): FullPresetModel {
        val dir = File(sessionDir, UUID.randomUUID().toString())
        if (!dir.mkdirs()) throw IOException("Cannot create model cache directory")
        val file = File(dir, "model.$extension")
        var complete = false
        try {
            val input = openInput() ?: throw WorkflowFailure(R.string.full_presets_document_read_failed)
            val context = currentCoroutineContext()
            input.use { source ->
                file.outputStream().use { target ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        context.ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_MODEL_BYTES) throw WorkflowFailure(R.string.full_presets_model_too_large)
                        target.write(buffer, 0, count)
                    }
                    if (total == 0L) throw WorkflowFailure(R.string.full_presets_model_empty)
                }
            }
            if (extension == "3mf") {
                when (fullPreset3mfArchiveError(file)) {
                    FullPreset3mfArchiveError.INVALID -> throw WorkflowFailure(R.string.full_presets_3mf_invalid)
                    FullPreset3mfArchiveError.TOO_LARGE -> throw WorkflowFailure(R.string.full_presets_3mf_too_large)
                    null -> Unit
                }
            }
            complete = true
            return FullPresetModel(displayName?.takeIf { it.isNotBlank() } ?: file.name, file, sourceUrl)
        } finally {
            if (!complete) dir.deleteRecursively()
        }
    }

    private suspend fun runNativeSlice(
        manifest: File,
        model: FullPresetModel,
        cancellation: SliceCancellation,
    ): SliceResult? {
        if (cancellation.requested) return null
        if (!NativeLibrary.isLoaded) throw WorkflowFailure(R.string.full_presets_engine_unavailable)
        // JNI writes output.gcode next to the loaded model. Each model has a private cache directory.
        val expectedOutput = File(model.file.parentFile, "output.gcode")
        if (expectedOutput.exists() && !expectedOutput.delete()) throw IOException("Cannot remove previous output")
        val native = NativeLibrary()
        var keepOutput = false
        try {
            if (!native.loadProfile(manifest.absolutePath)) {
                throw WorkflowFailure(R.string.full_presets_profile_rejected)
            }
            currentCoroutineContext().ensureActive()
            if (cancellation.requested) return null
            if (!native.loadModel(model.file.absolutePath) || native.getModelInfo() == null) {
                throw WorkflowFailure(R.string.full_presets_model_rejected)
            }
            if (model.file.extension == "3mf" && native.nativeGetPlateCount() > 1) {
                throw WorkflowFailure(R.string.full_presets_multiple_plates)
            }
            currentCoroutineContext().ensureActive()
            if (cancellation.requested) return null
            native.progressListener = { percentage, _ ->
                cancellation.nativeStarted(native)
                mutableState.update {
                    if (it.operation == FullPresetOperation.SLICING) it.copy(progress = percentage.coerceIn(0, 100))
                    else it
                }
            }
            // A loaded v2 manifest supplies the full config. The engine centers STL on the preset's bed.
            val result = native.slice(SliceConfig())
            if (cancellation.requested || result?.cancelled == true) return null
            if (result?.success != true) {
                val error = fullPresetSliceFailure(result?.errorMessage)
                throw WorkflowFailure(error.message, error.detail)
            }
            if (File(result.gcodePath).canonicalFile != expectedOutput.canonicalFile ||
                !expectedOutput.isFile || expectedOutput.length() == 0L
            ) {
                throw WorkflowFailure(R.string.full_presets_output_missing)
            }
            keepOutput = true
            return result
        } finally {
            native.progressListener = null
            cancellation.nativeFinished()
            try {
                native.clearModel()
            } finally {
                native.loadProfile("")
                if (!keepOutput) expectedOutput.delete()
            }
        }
    }

    override fun onCleared() {
        sliceCancellation?.request()
        val job = operationJob
        // Native calls cannot be interrupted by coroutine cancellation. Join before deleting their inputs.
        CoroutineScope(Dispatchers.IO).launch {
            job?.join()
            sessionDir.deleteRecursively()
        }
        super.onCleared()
    }

    private class WorkflowFailure(@StringRes val label: Int, val detail: String? = null) : Exception()

    private class SliceCancellation {
        @Volatile var requested = false
            private set

        private var activeNative: NativeLibrary? = null
        private var signalled = false

        @Synchronized fun request() {
            requested = true
            signalIfStarted()
        }

        @Synchronized fun nativeStarted(native: NativeLibrary) {
            // The first progress callback arrives after native resets its cancellation flag.
            activeNative = native
            signalIfStarted()
        }

        @Synchronized fun nativeFinished() {
            activeNative = null
        }

        private fun signalIfStarted() {
            val native = activeNative ?: return
            if (requested && !signalled) {
                signalled = true
                native.cancelSlice()
            }
        }
    }

    companion object {
        const val MAX_FILAMENTS = 16
        private const val MAX_MODEL_BYTES = 64L * 1024 * 1024
    }
}

internal fun fullPresetSliceFailure(errorMessage: String?): FullPresetNotice {
    val detail = errorMessage?.trim()?.takeIf { it.isNotEmpty() }
    // The native engine prefixes std::exception diagnostics before passing them through JNI.
    val message = when (detail?.removePrefix("Error: ")) {
        "Model references an unavailable filament",
        "Color paint references an unavailable filament" -> R.string.full_presets_slice_filament_slots
        "Geometry-only presets support color paint on filament slots 1 through 4" ->
            R.string.full_presets_slice_color_paint_slots
        "Geometry-only presets do not support painted supports, seams, fuzzy skin or exterior facets" ->
            R.string.full_presets_slice_other_paint_unsupported
        "Geometry-only presets do not support painted volumes" -> R.string.full_presets_slice_paint_unsupported
        else -> R.string.full_presets_slice_failed
    }
    return FullPresetNotice(message, detail?.take(MAX_ERROR_LENGTH))
}

internal fun fullPresetModelSourceUrl(sourceUrl: String?): String? {
    if (sourceUrl.isNullOrBlank()) return null
    return try {
        publicDownloadUrl(sourceUrl).takeIf { it.isHttps }?.toString()
    } catch (_: IllegalArgumentException) {
        null
    }
}
