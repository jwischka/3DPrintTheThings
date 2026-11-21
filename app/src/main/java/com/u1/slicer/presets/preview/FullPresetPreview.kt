package com.u1.slicer.presets.preview

import android.graphics.Color
import android.util.Log
import androidx.annotation.StringRes
import com.u1.slicer.NativeLibrary
import com.u1.slicer.R
import com.u1.slicer.viewer.MeshData
import com.u1.slicer.viewer.ModelCameraPose
import com.u1.slicer.viewer.NativeRenderBatch
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import org.json.JSONException
import org.json.JSONObject

internal sealed interface FullPresetPreview {
    data object Empty : FullPresetPreview
    data object Loading : FullPresetPreview
    data class Ready(val mesh: MeshData) : FullPresetPreview {
        // The UI thread retains the camera for this import across viewport recreation.
        var cameraPose: ModelCameraPose? = null
    }
    data class Unavailable(@StringRes val reason: Int) : FullPresetPreview
}

internal object FullPresetPreviewLoader {
    // Invalid inputs are reported by import. Preview-only failures leave the copied input sliceable.
    internal class InvalidModel(@StringRes val reason: Int) : Exception()

    suspend fun load(file: File): FullPresetPreview {
        if (!NativeLibrary.isLoaded) return FullPresetPreview.Unavailable(R.string.full_presets_engine_unavailable)
        return try {
            val (mesh, filamentColors) = NativeLibrary.previewMutex.withLock {
                val native = NativeLibrary()
                try {
                    if (!native.loadModel(file.absolutePath)) {
                        throw InvalidModel(R.string.full_presets_model_rejected)
                    }
                    currentCoroutineContext().ensureActive()
                    val colors = if (file.extension == "3mf") {
                        if (native.nativeGetPlateCount() > 1) {
                            throw InvalidModel(R.string.full_presets_multiple_plates)
                        }
                        readFilamentColors(native.nativeGetProjectConfig())
                    } else {
                        emptyList()
                    }
                    readMesh(native) to colors
                } finally {
                    native.clearModel()
                }
            }
            val context = currentCoroutineContext()
            context.ensureActive()
            if (mesh == null) {
                FullPresetPreview.Unavailable(R.string.full_presets_preview_unavailable)
            } else {
                // Scene batches emit file-indexed material IDs before the native mesh is compacted.
                mesh.colorFullPresetPreview(previewPalette(filamentColors)) { context.ensureActive() }
                FullPresetPreview.Ready(mesh)
            }
        } catch (error: InvalidModel) {
            throw error
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: LinkageError) {
            Log.w(TAG, "Preview engine unavailable", error)
            FullPresetPreview.Unavailable(R.string.full_presets_engine_unavailable)
        } catch (error: Exception) {
            Log.w(TAG, "Failed to prepare model preview", error)
            FullPresetPreview.Unavailable(R.string.full_presets_preview_unavailable)
        }
    }

    private suspend fun readMesh(native: NativeLibrary): MeshData? {
        val scene = native.buildPrepareRenderScene()
        if (scene == 0L) return null
        try {
            while (!native.nativeIsPrepareRenderSceneComplete(scene)) delay(16L)
            val batchCount = native.nativeGetPrepareRenderSceneBatchCount(scene)
            val batchSizes = IntArray(batchCount) { index ->
                native.nativeGetPrepareRenderSceneTriangleCount(scene, index)
            }
            val triangleCount = batchSizes.sumOf { it.toLong() }
            if (!canCopyFullPresetPreview(triangleCount)) {
                if (triangleCount > MAX_FULL_PRESET_PREVIEW_TRIANGLES) {
                    Log.w(TAG, "Preview triangle count $triangleCount exceeds the snapshot limit $MAX_FULL_PRESET_PREVIEW_TRIANGLES")
                }
                return null
            }
            // The worker can complete with partial batches when its native build fails.
            val completedSizes = native.nativeGetPreviewBatchTriangleCounts() ?: return null
            if (completedSizes.filter { it > 0 } != batchSizes.toList()) return null
            val context = currentCoroutineContext()
            val batches = ArrayList<NativeRenderBatch>(batchCount)
            val bounds = FloatArray(6) { if (it < 3) Float.POSITIVE_INFINITY else Float.NEGATIVE_INFINITY }
            for ((index, triangles) in batchSizes.withIndex()) {
                context.ensureActive()
                if (triangles <= 0) return null
                val geometry = native.nativeGetPrepareRenderSceneGeometryBuffer(scene, index)
                    ?.copyDirect(triangles * 3 * MeshData.BYTES_PER_VERTEX) ?: return null
                val materials = native.nativeGetPrepareRenderSceneMaterialBuffer(scene, index)
                    ?.copyDirect(triangles) ?: return null
                val batchBounds = native.nativeGetPrepareRenderSceneBoundingBox(scene, index) ?: return null
                if (batchBounds.size != bounds.size || batchBounds.any { !it.isFinite() }) return null
                for (axis in 0..2) {
                    if (batchBounds[axis] > batchBounds[axis + 3]) return null
                    bounds[axis] = minOf(bounds[axis], batchBounds[axis])
                    bounds[axis + 3] = maxOf(bounds[axis + 3], batchBounds[axis + 3])
                }
                batches.add(NativeRenderBatch(geometry.asFloatBuffer(), materials, triangles, batchBounds))
            }
            val modifierStart = native.nativeGetPreviewModifierBlockStart()
            if (modifierStart == 0 || modifierStart >= triangleCount) return null
            return MeshData(
                batches = batches,
                minX = bounds[0], minY = bounds[1], minZ = bounds[2],
                maxX = bounds[3], maxY = bounds[4], maxZ = bounds[5],
                modifierBlockStartTriangle = modifierStart.takeIf { it > 0 },
            )
        } catch (cancelled: CancellationException) {
            native.cancelPreviewMesh()
            throw cancelled
        } finally {
            // Release joins the worker before the caller clears the engine's live model.
            native.nativeReleasePrepareRenderScene(scene)
        }
    }

    internal fun ByteBuffer.copyDirect(bytes: Int): ByteBuffer? {
        if (remaining() != bytes) return null
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()).apply {
            put(this@copyDirect.duplicate())
            flip()
        }
    }

    private fun readFilamentColors(json: String?): List<String> = try {
        val colors = json?.let { JSONObject(it).optJSONArray("filamentColours") }
        List(minOf(colors?.length() ?: 0, 256)) { colors?.optString(it).orEmpty() }
    } catch (_: JSONException) {
        emptyList()
    }

    private val defaultColors = listOf("#59D4BE", "#F0A75D", "#8BABF6", "#ED86A9", "#D7D96E", "#B39AF4")
    private const val TAG = "FullPresetPreview"

    private fun previewPalette(filamentColors: List<String>): List<FloatArray> = List(256) { slot ->
        val color = filamentColors.getOrNull(slot)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            try {
                Color.parseColor(it)
            } catch (_: IllegalArgumentException) {
                null
            }
        } ?: Color.parseColor(defaultColors[slot % defaultColors.size])
        floatArrayOf(Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f, 1f)
    }
}

// Interleaved vertex colors keep a 700K-triangle snapshot near the retained CPU and GPU cost
// of 500K triangles with a separate color buffer. Native scene buffers briefly coexist.
private const val MAX_FULL_PRESET_PREVIEW_TRIANGLES = 700_000L

internal fun canCopyFullPresetPreview(triangleCount: Long): Boolean =
    triangleCount in 1L..MAX_FULL_PRESET_PREVIEW_TRIANGLES

internal fun MeshData.colorFullPresetPreview(palette: List<FloatArray>, checkCancellation: () -> Unit) {
    val modifierStart = modifierBlockStartTriangle ?: Int.MAX_VALUE
    var firstTriangle = 0
    for (batch in batches) {
        val materials = requireNotNull(batch.materialIndices)
        val geometry = batch.geometry
        for (triangle in 0 until batch.triangleCount) {
            if (triangle % 4096 == 0) checkCancellation()
            val slot = materials.get(triangle).toInt() and 0xFF
            val color = if (firstTriangle + triangle >= modifierStart) {
                MeshData.MODIFIER_PREVIEW_COLOR
            } else {
                palette[slot.coerceAtMost(palette.lastIndex)]
            }
            for (vertex in 0 until 3) {
                val offset = (triangle * 3 + vertex) * MeshData.FLOATS_PER_VERTEX + 6
                geometry.put(offset, color[0])
                geometry.put(offset + 1, color[1])
                geometry.put(offset + 2, color[2])
                geometry.put(offset + 3, color[3])
            }
        }
        firstTriangle += batch.triangleCount
    }
}
