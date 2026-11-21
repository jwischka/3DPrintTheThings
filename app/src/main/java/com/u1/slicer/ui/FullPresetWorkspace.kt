package com.u1.slicer.ui

import android.icu.text.MeasureFormat
import android.icu.text.NumberFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.u1.slicer.R
import com.u1.slicer.browse.openExternalBrowser
import com.u1.slicer.data.SliceResult
import com.u1.slicer.presets.FullPresetOutput
import com.u1.slicer.presets.FullPresetUiState
import com.u1.slicer.presets.preview.FullPresetModelPreview
import com.u1.slicer.presets.preview.FullPresetPreview
import com.u1.slicer.viewer.MeshData
import kotlin.math.roundToLong

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
            val compact = maxWidth < 640.dp
            val short = maxHeight < 320.dp
            val verticalPadding = if (maxHeight < 280.dp) 8.dp else 16.dp
            val workspaceHeight = (maxHeight - verticalPadding * 2).coerceAtLeast(0.dp)
            val sidebarScroll = rememberScrollState()
            val sidebarActions: @Composable () -> Unit = {
                Surface(shape = panelShape, color = MaterialTheme.colorScheme.surface, content = sideActions)
            }
            Row(
                modifier = Modifier.fillMaxSize().padding(
                    horizontal = if (compact) 12.dp else 20.dp,
                    vertical = verticalPadding,
                ),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
            ) {
                ModelPanel(
                    state = state,
                    onImportModel = onImportModel,
                    modifier = Modifier.weight(1.45f).fillMaxHeight(),
                    viewportModifier = Modifier.weight(1f),
                    metadataMaxHeight = if (short) workspaceHeight * (2f / 3f) else null,
                )
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().then(
                        if (short) Modifier.verticalScroll(sidebarScroll) else Modifier,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (short) sidebarActions()
                    Column(
                        modifier = if (short) {
                            Modifier.fillMaxWidth()
                        } else {
                            Modifier.weight(1f).verticalScroll(sidebarScroll)
                        },
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        state.output?.let { SliceResultCard(it, state.operation == null, onSliceAgain, compact) }
                        PrintSetupCard(state, onOpenSettings, compact)
                    }
                    if (!short) sidebarActions()
                }
            }
        } else {
            val viewportHeight = (maxHeight * 0.63f).coerceIn(180.dp, 540.dp)
                .coerceAtMost(maxHeight * 0.86f)
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
    metadataMaxHeight: Dp? = null,
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
                val metadataModifier = if (metadataMaxHeight == null) {
                    Modifier
                } else {
                    Modifier.heightIn(max = metadataMaxHeight).verticalScroll(rememberScrollState())
                }
                ModelMetadata(
                    name = model.name,
                    sourceUrl = model.sourceUrl,
                    mesh = (state.preview as? FullPresetPreview.Ready)?.mesh,
                    replaceEnabled = state.operation == null,
                    onReplace = onImportModel,
                    modifier = metadataModifier,
                )
            }
        }
    }
}

@Composable
private fun ModelMetadata(
    name: String,
    sourceUrl: String?,
    mesh: MeshData?,
    replaceEnabled: Boolean,
    onReplace: () -> Unit,
    modifier: Modifier,
) {
    val sourceLinkModifier = if (sourceUrl == null) {
        Modifier
    } else {
        val context = LocalContext.current
        Modifier.testTag("full-presets-model-source").clickable(
            onClickLabel = stringResource(R.string.full_presets_open_model_source),
            role = Role.Button,
        ) {
            if (!openExternalBrowser(context, sourceUrl)) {
                Toast.makeText(context, R.string.full_presets_model_source_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
    BoxWithConstraints(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
    ) {
        val stacked = maxWidth < 208.dp * LocalDensity.current.fontScale
        val label: @Composable (Modifier) -> Unit = { labelModifier ->
            Column(labelModifier) {
                Text(
                    name,
                    modifier = sourceLinkModifier,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (sourceUrl == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                    textDecoration = if (sourceUrl == null) null else TextDecoration.Underline,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                mesh?.let { ModelDimensions(it, wrap = stacked) }
            }
        }
        val replaceButton: @Composable () -> Unit = {
            TextButton(
                onClick = onReplace,
                enabled = replaceEnabled,
                modifier = Modifier.testTag("full-presets-import-model").heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.full_presets_change_model))
            }
        }
        if (stacked) {
            Column(Modifier.fillMaxWidth()) {
                label(Modifier.fillMaxWidth())
                Box(Modifier.align(Alignment.End)) { replaceButton() }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                label(Modifier.weight(1f))
                replaceButton()
            }
        }
    }
}

@Composable
private fun ModelDimensions(mesh: MeshData, wrap: Boolean) {
    val sizes = listOf(mesh.sizeX, mesh.sizeY, mesh.sizeZ)
    if (sizes.any { !it.isFinite() || it < 0f }) return

    val locale = LocalConfiguration.current.locales[0]
    val number = remember(locale) {
        NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = 1
            isGroupingUsed = false
        }
    }
    val measure = remember(locale, number) { MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.SHORT, number) }
    Text(
        stringResource(
            R.string.full_presets_model_dimensions,
            number.format(mesh.sizeX.toDouble()),
            number.format(mesh.sizeY.toDouble()),
            measure.formatMeasures(Measure(mesh.sizeZ, MeasureUnit.MILLIMETER)),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().testTag("full-presets-model-dimensions"),
        maxLines = if (wrap) Int.MAX_VALUE else 1,
        overflow = if (wrap) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
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
private fun PrintSetupCard(state: FullPresetUiState, onOpen: () -> Unit, compact: Boolean = false) {
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
            modifier = Modifier.padding(if (compact) 12.dp else 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (!compact) PanelIcon(Icons.Outlined.Print)
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
                val mapping = state.filamentMapping
                val filaments = if (mapping != null) {
                    mapping.targets.toSortedMap().map { (source, target) ->
                        val path = mapping.physicalPresets[target - 1]
                        stringResource(R.string.full_presets_mapping_summary, source, target, names[path] ?: path)
                    }
                } else listOfNotNull(when (slots.size) {
                    0 -> null
                    1 -> names[slots.single()]
                    else -> pluralStringResource(R.plurals.full_presets_slot_count, slots.size, slots.size)
                })
                for (filament in filaments) {
                    Text(
                        filament,
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
private fun SliceResultCard(
    output: FullPresetOutput,
    enabled: Boolean,
    onSliceAgain: () -> Unit,
    compact: Boolean = false,
) {
    val layers = output.result.totalLayers
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("full-presets-result"),
        shape = panelShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        val horizontalPadding = if (compact) 12.dp else 16.dp
        Row(
            modifier = Modifier.padding(start = horizontalPadding, top = 12.dp, end = horizontalPadding, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (!compact) PanelIcon(Icons.Outlined.CheckCircle)
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
                SliceEstimates(output.result)
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

@Composable
private fun SliceEstimates(result: SliceResult) {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.SHORT) }
    val time = result.estimatedTimeSeconds
    val grams = result.estimatedFilamentGrams
    val millimeters = result.estimatedFilamentMm
    if (time.isFinite() && time > 0f) {
        val seconds = time.roundToLong().coerceAtLeast(1)
        val minutes = seconds / 60
        val hours = minutes / 60
        val duration = when {
            hours > 0 && minutes % 60 > 0 -> formatter.formatMeasures(
                Measure(hours, MeasureUnit.HOUR), Measure(minutes % 60, MeasureUnit.MINUTE),
            )
            hours > 0 -> formatter.formatMeasures(Measure(hours, MeasureUnit.HOUR))
            minutes > 0 -> formatter.formatMeasures(Measure(minutes, MeasureUnit.MINUTE))
            else -> formatter.formatMeasures(Measure(seconds, MeasureUnit.SECOND))
        }
        Text(
            stringResource(R.string.full_presets_estimated_time, duration),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("full-presets-estimated-time"),
        )
    }
    val filament = when {
        grams.isFinite() && grams > 0f -> Measure(grams, MeasureUnit.GRAM)
        millimeters.isFinite() && millimeters >= 1000f -> Measure(millimeters / 1000f, MeasureUnit.METER)
        millimeters.isFinite() && millimeters > 0f -> Measure(millimeters, MeasureUnit.MILLIMETER)
        else -> null
    }
    filament?.let {
        Text(
            stringResource(R.string.full_presets_estimated_filament, formatter.formatMeasures(it)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("full-presets-estimated-filament"),
        )
    }
}
