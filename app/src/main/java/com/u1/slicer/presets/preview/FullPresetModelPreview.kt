package com.u1.slicer.presets.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.u1.slicer.R
import com.u1.slicer.viewer.ModelViewerView

internal const val PREVIEW_MODEL_SURFACE = "full-presets-model-preview"

@Composable
internal fun FullPresetModelPreview(preview: FullPresetPreview, modifier: Modifier = Modifier) {
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when (preview) {
            FullPresetPreview.Empty -> Unit
            FullPresetPreview.Loading -> Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.full_presets_preview_loading))
            }
            is FullPresetPreview.Unavailable -> Text(
                stringResource(preview.reason),
                Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            is FullPresetPreview.Ready -> PreviewSurface(preview)
        }
    }
}

@Composable
private fun PreviewSurface(preview: FullPresetPreview.Ready) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val background = MaterialTheme.colorScheme.surfaceVariant.toArgb()
    val description = stringResource(R.string.full_presets_preview_gestures)
    key(preview, lifecycle, background) {
        var viewer by remember { mutableStateOf<ModelViewerView?>(null) }
        val lifecycleObserver = remember {
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> viewer?.onResume()
                    Lifecycle.Event.ON_PAUSE -> viewer?.onPause()
                    else -> Unit
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { context ->
                    ModelViewerView(context, modelPreviewBackground = background).also { view ->
                        view.preserveEGLContextOnPause = true
                        view.renderer.pendingModelCameraPose = preview.cameraPose
                        view.onCameraChanged = { state -> preview.cameraPose = view.camera.modelPose(state) }
                        view.setMesh(preview.mesh)
                        viewer = view
                        lifecycle.addObserver(lifecycleObserver)
                        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onPause()
                    }
                },
                update = { it.contentDescription = description },
                onRelease = { view ->
                    lifecycle.removeObserver(lifecycleObserver)
                    view.onCameraChanged = null
                    view.renderer.releaseForDetach()
                    viewer = null
                },
                modifier = Modifier.fillMaxSize().testTag(PREVIEW_MODEL_SURFACE),
            )
            FilledTonalIconButton(
                onClick = {
                    preview.cameraPose = null
                    viewer?.renderer?.pendingModelCameraPose = null
                    viewer?.resetView()
                },
                enabled = viewer != null,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            ) {
                Icon(Icons.Default.FitScreen, stringResource(R.string.full_presets_preview_fit))
            }
        }
    }
}
