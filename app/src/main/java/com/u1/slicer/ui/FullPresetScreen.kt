package com.u1.slicer.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.u1.slicer.R
import com.u1.slicer.presets.FullPresetViewModel
import com.u1.slicer.ui.printer.experimental.ExperimentalPrinterDashboard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FullPresetScreen(onBack: () -> Unit) {
    val model: FullPresetViewModel = viewModel()
    val state by model.state.collectAsState()
    val enabled = state.operation == null
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var showPrinter by rememberSaveable { mutableStateOf(false) }
    val savedContent = rememberSaveableStateHolder()
    val screen = LocalConfiguration.current
    val splitWorkspace = screen.screenWidthDp >= 840 ||
        (screen.screenWidthDp >= 600 && screen.screenWidthDp > screen.screenHeightDp)

    val bundleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(model::importBundle)
    }
    val modelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(model::importModel)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {
        it?.let(model::exportGcode)
    }
    val importModel = { modelLauncher.launch(arrayOf("*/*")) }
    val importBundle = { bundleLauncher.launch(arrayOf("*/*")) }
    val primaryActions: @Composable () -> Unit = {
        PrepareActionBar(
            state = state,
            onImportModel = importModel,
            onImportBundle = importBundle,
            onSlice = model::slice,
            onCancel = model::cancelSlice,
            onExport = { exportLauncher.launch(it) },
        )
    }

    val navigateBack: () -> Unit = {
        if (showPrinter) showPrinter = false
        else if (enabled) onBack()
    }
    BackHandler(enabled = !settingsOpen, onBack = navigateBack)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(if (showPrinter) R.string.full_presets_printer_tab else R.string.full_presets_title),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() },
                    )
                },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                navigationIcon = {
                    IconButton(onClick = navigateBack, enabled = showPrinter || enabled) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.full_presets_back))
                    }
                },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
                Column(
                    modifier = Modifier.windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                    ),
                ) {
                    if (!showPrinter && !splitWorkspace) primaryActions()
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    FullPresetNavigation(showPrinter, onSelectPrinter = { showPrinter = it })
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (showPrinter) {
                savedContent.SaveableStateProvider("printer") {
                    ExperimentalPrinterDashboard(Modifier.widthIn(max = 1120.dp).fillMaxSize())
                }
            } else {
                savedContent.SaveableStateProvider("prepare") {
                    PrepareWorkspace(
                        state = state,
                        onImportModel = importModel,
                        onOpenSettings = { settingsOpen = true },
                        onSliceAgain = model::slice,
                        sideActions = primaryActions.takeIf { splitWorkspace },
                    )
                }
            }
        }
    }
    if (settingsOpen) {
        FullPresetSettingsSheet(
            state = state,
            viewModel = model,
            onImportBundle = {
                settingsOpen = false
                importBundle()
            },
            onDismiss = { settingsOpen = false },
        )
    }
}

@Composable
private fun FullPresetNavigation(showPrinter: Boolean, onSelectPrinter: (Boolean) -> Unit) {
    val height = 64.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.4f)
    val colors = NavigationBarItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        selectedTextColor = MaterialTheme.colorScheme.primary,
        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
    )
    NavigationBar(
        modifier = Modifier.height(height),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        NavigationBarItem(
            selected = !showPrinter,
            onClick = { onSelectPrinter(false) },
            icon = { Icon(Icons.Outlined.ViewInAr, contentDescription = null) },
            label = { Text(stringResource(R.string.full_presets_title)) },
            colors = colors,
            modifier = Modifier.testTag("full-presets-tab-prepare"),
        )
        NavigationBarItem(
            selected = showPrinter,
            onClick = { onSelectPrinter(true) },
            icon = { Icon(Icons.Outlined.Print, contentDescription = null) },
            label = { Text(stringResource(R.string.full_presets_printer_tab)) },
            colors = colors,
            modifier = Modifier.testTag("full-presets-tab-printer"),
        )
    }
}
