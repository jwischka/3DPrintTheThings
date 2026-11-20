package com.u1.slicer.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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

private enum class FullPresetTab(@StringRes val label: Int, val icon: ImageVector, val tag: String) {
    PREPARE(R.string.full_presets_title, Icons.Outlined.ViewInAr, "full-presets-tab-prepare"),
    PRINTER(R.string.full_presets_printer_tab, Icons.Outlined.Print, "full-presets-tab-printer"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FullPresetScreen(onBack: () -> Unit) {
    val model: FullPresetViewModel = viewModel()
    val state by model.state.collectAsState()
    val enabled = state.operation == null
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(FullPresetTab.PREPARE) }
    val savedContent = rememberSaveableStateHolder()
    val screen = LocalConfiguration.current
    val showRail = screen.screenWidthDp >= 840 ||
        (screen.screenWidthDp >= 560 && screen.orientation == Configuration.ORIENTATION_LANDSCAPE)

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
        if (tab == FullPresetTab.PRINTER) tab = FullPresetTab.PREPARE
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
                        stringResource(tab.label),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() },
                    )
                },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                navigationIcon = {
                    IconButton(onClick = navigateBack, enabled = tab == FullPresetTab.PRINTER || enabled) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.full_presets_back))
                    }
                },
            )
        },
        bottomBar = {
            if (!showRail) {
                Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
                    Column(
                        modifier = Modifier.windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                        ),
                    ) {
                        if (tab == FullPresetTab.PREPARE) primaryActions()
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        FullPresetBottomNavigation(tab, onSelect = { tab = it })
                    }
                }
            }
        },
    ) { padding ->
        Row(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
        ) {
            if (showRail) {
                FullPresetNavigationRail(tab, onSelect = { tab = it })
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                savedContent.SaveableStateProvider(tab.name) {
                    when (tab) {
                        FullPresetTab.PRINTER -> ExperimentalPrinterDashboard(
                            Modifier.widthIn(max = 1120.dp).fillMaxSize(),
                        )
                        FullPresetTab.PREPARE -> PrepareWorkspace(
                            state = state,
                            onImportModel = importModel,
                            onOpenSettings = { settingsOpen = true },
                            onSliceAgain = model::slice,
                            sideActions = primaryActions.takeIf { showRail },
                        )
                    }
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
private fun FullPresetBottomNavigation(selected: FullPresetTab, onSelect: (FullPresetTab) -> Unit) {
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
        FullPresetTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(stringResource(tab.label)) },
                colors = colors,
                modifier = Modifier.testTag(tab.tag),
            )
        }
    }
}

@Composable
private fun FullPresetNavigationRail(selected: FullPresetTab, onSelect: (FullPresetTab) -> Unit) {
    val colors = NavigationRailItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        selectedTextColor = MaterialTheme.colorScheme.primary,
        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
    )
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surface,
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        FullPresetTab.entries.forEach { tab ->
            NavigationRailItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(stringResource(tab.label)) },
                colors = colors,
                modifier = Modifier.testTag(tab.tag),
            )
        }
    }
}
