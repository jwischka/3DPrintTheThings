package com.u1.slicer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.u1.slicer.R
import com.u1.slicer.presets.FullPresetOutput
import com.u1.slicer.presets.FullPresetUiState
import com.u1.slicer.presets.preview.FullPresetModelPreview
import com.u1.slicer.presets.preview.FullPresetPreview

private val panelShape = RoundedCornerShape(22.dp)

@Composable
internal fun PrepareWorkspace(
    state: FullPresetUiState,
    onImportModel: () -> Unit,
    onOpenSettings: () -> Unit,
    onSliceAgain: () -> Unit,
    sideActions: (@Composable () -> Unit)?,
) {
    BoxWithConstraints(Modifier.widthIn(max = 1120.dp).fillMaxSize()) {
        if (sideActions != null) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ModelPanel(
                    state = state,
                    onImportModel = onImportModel,
                    modifier = Modifier.weight(1.45f).fillMaxHeight(),
                    viewportModifier = Modifier.weight(1f),
                )
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        state.output?.let { SliceResultCard(it, state.operation == null, onSliceAgain) }
                        PrintSetupCard(state, onOpenSettings)
                    }
                    Surface(shape = panelShape, color = MaterialTheme.colorScheme.surface) {
                        sideActions()
                    }
                }
            }
        } else {
            val viewportHeight = (maxHeight * 0.63f).coerceIn(180.dp, 540.dp)
            val scrollState = rememberScrollState()
            val modelPath = state.model?.file?.absolutePath
            var lastModelPath by rememberSaveable { mutableStateOf(modelPath) }
            LaunchedEffect(modelPath) {
                if (modelPath != lastModelPath) {
                    scrollState.scrollTo(0)
                    lastModelPath = modelPath
                }
            }
            Column(
                modifier = Modifier.fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ModelPanel(state, onImportModel, viewportModifier = Modifier.height(viewportHeight))
                state.output?.let { SliceResultCard(it, state.operation == null, onSliceAgain) }
                PrintSetupCard(state, onOpenSettings)
            }
        }
    }
}

@Composable
private fun ModelPanel(
    state: FullPresetUiState,
    onImportModel: () -> Unit,
    viewportModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = panelShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            if (state.preview == FullPresetPreview.Empty) {
                EmptyViewport(viewportModifier.fillMaxWidth())
            } else {
                FullPresetModelPreview(state.preview, viewportModifier.fillMaxWidth())
            }
            state.model?.let { model ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        model.name,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(
                        onClick = onImportModel,
                        enabled = state.operation == null,
                        modifier = Modifier.testTag("full-presets-import-model").heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.full_presets_change_model))
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyViewport(modifier: Modifier) {
    val dot = MaterialTheme.colorScheme.outlineVariant
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceVariant) {
        Box(
            modifier = Modifier.fillMaxSize().drawBehind {
                val step = 16.dp.toPx()
                val radius = 0.85.dp.toPx()
                var x = (size.width % step) * 0.5f
                while (x < size.width) {
                    var y = (size.height % step) * 0.5f
                    while (y < size.height) {
                        drawCircle(dot, radius, Offset(x, y))
                        y += step
                    }
                    x += step
                }
            }.padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            EmptyViewportLabel()
        }
    }
}

@Composable
private fun EmptyViewportLabel() {
    Column(
        modifier = Modifier.widthIn(max = 340.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PanelIcon(Icons.Outlined.ViewInAr, size = 56.dp)
        Text(
            stringResource(R.string.full_presets_empty_plate),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.full_presets_supported_models),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PrintSetupCard(state: FullPresetUiState, onOpen: () -> Unit) {
    val bundle = state.bundle
    val selection = state.selection
    val names = remember(bundle) { bundle?.catalog.orEmpty().associate { it.path to it.name } }
    val printer = names[selection?.printer]
    val process = names[selection?.process]
    val slots = selection?.filaments.orEmpty()
    Surface(
        onClick = onOpen,
        enabled = state.operation == null,
        modifier = Modifier.fillMaxWidth().testTag("full-presets-settings"),
        shape = panelShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            PanelIcon(Icons.Outlined.Print)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.full_presets_setup),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    printer ?: stringResource(R.string.full_presets_no_preset),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                process?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val filament = when (slots.size) {
                    0 -> null
                    1 -> names[slots.single()]
                    else -> pluralStringResource(R.plurals.full_presets_slot_count, slots.size, slots.size)
                }
                filament?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                Icons.Outlined.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PanelIcon(icon: ImageVector, size: Dp = 40.dp) {
    Surface(
        modifier = Modifier.size(size),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null)
        }
    }
}

@Composable
private fun SliceResultCard(output: FullPresetOutput, enabled: Boolean, onSliceAgain: () -> Unit) {
    val layers = output.result.totalLayers
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("full-presets-result"),
        shape = panelShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            PanelIcon(Icons.Outlined.CheckCircle)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    pluralStringResource(R.plurals.full_presets_slice_complete, layers, layers),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    output.filename,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(
                    onClick = onSliceAgain,
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("full-presets-slice-again"),
                ) {
                    Text(stringResource(R.string.full_presets_slice_again))
                }
            }
        }
    }
}
