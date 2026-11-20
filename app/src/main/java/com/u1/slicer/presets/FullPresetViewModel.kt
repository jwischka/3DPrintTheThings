package com.u1.slicer.presets

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.u1.slicer.NativeLibrary
import com.u1.slicer.R
import com.u1.slicer.data.ModelFileNaming
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.data.SliceResult
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

internal data class FullPresetModel(val name: String, val file: File)

internal data class FullPresetOutput(val filename: String, val result: SliceResult)

internal enum class FullPresetOperation(@StringRes val label: Int) {
    READING_BUNDLES(R.string.full_presets_reading_bundles),
    IMPORTING_BUNDLE(R.string.full_presets_importing_bundle),
    IMPORTING_MODEL(R.string.full_presets_importing_model),
    SLICING(R.string.full_presets_slicing),
    CANCELLING(R.string.full_presets_cancelling),
    EXPORTING(R.string.full_presets_exporting),
}

internal data class FullPresetNotice(@StringRes val message: Int, val detail: String? = null)

internal data class FullPresetUiState(
    val bundles: List<FullPresetStore.Bundle> = emptyList(),
    val bundleId: String? = null,
    val selection: FullPresetSelection? = null,
    val model: FullPresetModel? = null,
    val output: FullPresetOutput? = null,
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
    private val sessionDir by lazy { File(application.cacheDir, "full-presets/${UUID.randomUUID()}") }
    private val mutableState = MutableStateFlow(FullPresetUiState())
    val state = mutableState.asStateFlow()

    // Mutations and operation ownership are on the main thread. Native progress only updates StateFlow.
    private var operationJob: Job? = null
    private var sliceCancellation: SliceCancellation? = null

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
                it.copy(bundles = bundles, bundleId = imported.id, selection = FullPresetSelection(imported))
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
            output = null,
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
            mutableState.value = snapshot.copy(selection = updated, output = null, error = null, status = null)
        }
    }

    fun importModel(uri: Uri) {
        runOperation(FullPresetOperation.IMPORTING_MODEL, R.string.full_presets_model_import_failed) {
            val oldModel = state.value.model
            val model = withContext(Dispatchers.IO) {
                copyModel(uri).also { oldModel?.file?.parentFile?.deleteRecursively() }
            }
            mutableState.update { it.copy(model = model) }
        }
    }

    fun slice() {
        val snapshot = state.value
        val bundle = snapshot.bundle ?: return
        val selection = snapshot.selection ?: return
        val model = snapshot.model ?: return
        runOperation(FullPresetOperation.SLICING, R.string.full_presets_slice_failed) {
            val cancellation = SliceCancellation()
            sliceCancellation = cancellation
            try {
                val result = withContext(Dispatchers.IO) {
                    // Competing activity instances can select the same bundle. Hold the lock from writing
                    // the shared manifest through loading it, so each slice uses its own selection.
                    NativeLibrary.previewMutex.withLock {
                        if (cancellation.requested) return@withLock null
                        val manifest = store.writeSelection(bundle.id, selection.printer, selection.process, selection.filaments)
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
                        runNativeSlice(manifest, model, cancellation)
                    }
                }
                // Cancellation can arrive after JNI finishes and before this result reaches the UI.
                if (result == null || cancellation.requested) {
                    if (result != null) withContext(Dispatchers.IO) { File(result.gcodePath).delete() }
                    mutableState.update { it.copy(status = R.string.full_presets_slice_cancelled) }
                } else {
                    val name = ModelFileNaming.baseName(model.name, model.file.name).take(128) + ".gcode"
                    mutableState.update { it.copy(output = FullPresetOutput(name, result)) }
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

    private fun runOperation(
        operation: FullPresetOperation,
        @StringRes failure: Int,
        invalidateOutput: Boolean = true,
        action: suspend () -> Unit,
    ) {
        val snapshot = state.value
        if (snapshot.operation != null) return
        mutableState.value = snapshot.copy(
            operation = operation,
            progress = null,
            output = snapshot.output.takeUnless { invalidateOutput },
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
    }

    private suspend fun copyModel(uri: Uri): FullPresetModel {
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        val extension = when (displayName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)) {
            "stl" -> "stl"
            "3mf" -> "3mf"
            else -> when (resolver.getType(uri)) {
                "model/stl", "application/sla", "application/vnd.ms-pki.stl" -> "stl"
                "model/3mf", "application/vnd.ms-3mfdocument" -> "3mf"
                else -> throw WorkflowFailure(R.string.full_presets_model_type)
            }
        }
        val dir = File(sessionDir, UUID.randomUUID().toString())
        if (!dir.mkdirs()) throw IOException("Cannot create model cache directory")
        val file = File(dir, "model.$extension")
        var complete = false
        try {
            val input = resolver.openInputStream(uri)
                ?: throw WorkflowFailure(R.string.full_presets_document_read_failed)
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
            return FullPresetModel(displayName?.takeIf { it.isNotBlank() } ?: file.name, file)
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
                val detail = result?.errorMessage?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_ERROR_LENGTH)
                throw WorkflowFailure(R.string.full_presets_slice_failed, detail)
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
        private const val MAX_ERROR_LENGTH = 500
    }
}
