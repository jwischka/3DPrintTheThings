package com.u1.slicer.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.u1.slicer.R
import com.u1.slicer.browse.BrowseScreen
import com.u1.slicer.browse.ModelSite
import com.u1.slicer.browse.ModelSites
import com.u1.slicer.browse.SiteBrowserScreen
import com.u1.slicer.browse.openExternalBrowser
import com.u1.slicer.library.ArchiveScreen
import com.u1.slicer.library.DownloadEntry
import com.u1.slicer.library.LibraryScreen
import com.u1.slicer.presets.FullPresetCatalogTransfer
import com.u1.slicer.presets.FullPresetCatalogViewModel
import com.u1.slicer.presets.FullPresetOperation
import com.u1.slicer.presets.FullPresetViewModel
import com.u1.slicer.ui.printer.experimental.ExperimentalPrinterDashboard
import java.io.IOException

private enum class FullPresetTab(@StringRes val label: Int, val icon: ImageVector, val tag: String) {
    PREPARE(R.string.full_presets_title, Icons.Outlined.ViewInAr, "full-presets-tab-prepare"),
    BROWSE(R.string.full_presets_browse_tab, Icons.Outlined.Search, "full-presets-tab-browse"),
    LIBRARY(R.string.full_presets_library_tab, Icons.Outlined.FolderOpen, "full-presets-tab-library"),
    PRINTER(R.string.full_presets_printer_tab, Icons.Outlined.Print, "full-presets-tab-printer");

    val isCatalog: Boolean get() = this == BROWSE || this == LIBRARY
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FullPresetScreen(
    onBack: () -> Unit,
    catalog: FullPresetCatalogViewModel = viewModel(),
) {
    val model: FullPresetViewModel = viewModel()
    val state by model.state.collectAsState()
    val enabled = state.operation == null
    val catalogState by catalog.state.collectAsState()
    val transfer = catalogState.transfer
    val catalogEnabled = enabled && transfer !is FullPresetCatalogTransfer.InProgress
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(FullPresetTab.PREPARE) }
    val savedContent = rememberSaveableStateHolder()
    var browserSiteId by rememberSaveable { mutableStateOf<String?>(null) }
    var browserUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var archiveHash by rememberSaveable { mutableStateOf<String?>(null) }
    var archiveSourceUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var catalogOrigin by rememberSaveable { mutableLongStateOf(0L) }
    val browserSite = browserSiteId?.let(ModelSites::find)
    val context = LocalContext.current
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
            onReviewPrint = model::openPrintReview,
            onExport = { exportLauncher.launch(it) },
        )
    }

    fun closeBrowser() {
        browserSiteId = null
        browserUrl = null
        savedContent.removeState("catalog-browser")
        catalogOrigin++
    }

    fun closeArchive() {
        archiveHash = null
        archiveSourceUrl = null
        catalogOrigin++
    }

    fun selectTab(next: FullPresetTab) {
        if (tab == next && browserSiteId == null && archiveHash == null) return
        tab = next
        closeBrowser()
        closeArchive()
    }

    fun openBrowser(site: ModelSite, url: String) {
        closeBrowser()
        browserSiteId = site.id
        browserUrl = url
    }

    fun openEntry(entry: DownloadEntry, sourceUrl: String?) {
        if (model.state.value.operation != null ||
            catalog.state.value.transfer is FullPresetCatalogTransfer.InProgress
        ) return
        if (entry.fileName.endsWith(".zip", ignoreCase = true)) {
            if (!tab.isCatalog) selectTab(FullPresetTab.LIBRARY)
            archiveHash = entry.sha256
            archiveSourceUrl = sourceUrl
            catalogOrigin++
            catalog.dismissTransfer()
            return
        }
        try {
            val file = catalog.store.fileFor(entry)
            if (!file.isFile || file.length() != entry.sizeBytes) throw IOException("Download missing")
            if (model.importModelFile(file, entry.fileName, sourceUrl)) {
                catalog.dismissTransfer()
                selectTab(FullPresetTab.PREPARE)
            }
        } catch (_: IOException) {
            catalog.showFailure(R.string.library_failed)
        } catch (_: IllegalArgumentException) {
            catalog.showFailure(R.string.library_failed)
        }
    }

    LaunchedEffect(transfer) {
        if (transfer is FullPresetCatalogTransfer.Ready && transfer.origin != null) {
            if (tab.isCatalog && transfer.origin == catalogOrigin && model.state.value.operation == null) {
                openEntry(transfer.entry, transfer.sourceUrl)
            } else {
                catalog.requireExplicitOpen(transfer)
            }
        }
    }

    val navigateBack: () -> Unit = {
        when {
            archiveHash != null -> closeArchive()
            browserSiteId != null -> closeBrowser()
            tab != FullPresetTab.PREPARE -> selectTab(FullPresetTab.PREPARE)
            enabled -> onBack()
        }
    }
    BackHandler(enabled = !settingsOpen, onBack = navigateBack)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            if (!tab.isCatalog) TopAppBar(
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
            val showCatalogStatus = transfer !is FullPresetCatalogTransfer.Idle || (tab.isCatalog && !enabled)
            if (!showRail || showCatalogStatus) {
                Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
                    Column(
                        modifier = Modifier.windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                        ),
                    ) {
                        if (showCatalogStatus) {
                            FullPresetCatalogTransferBar(
                                transfer = transfer,
                                workspaceBusy = !enabled,
                                onCancel = catalog::cancelTransfer,
                                onDismiss = catalog::dismissTransfer,
                                onOpen = { ready -> openEntry(ready.entry, ready.sourceUrl) },
                            )
                        }
                        if (!showRail) {
                            if (tab == FullPresetTab.PREPARE) primaryActions()
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            FullPresetBottomNavigation(tab, onSelect = ::selectTab)
                        }
                    }
                }
            }
        },
    ) { padding ->
        Row(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
        ) {
            if (showRail) {
                FullPresetNavigationRail(tab, onSelect = ::selectTab)
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                val currentArchive = archiveHash
                if (tab.isCatalog && currentArchive != null) {
                    Box(Modifier.widthIn(max = 920.dp).fillMaxSize()) {
                        ArchiveScreen(
                            store = catalog.store,
                            hash = currentArchive,
                            onBack = ::closeArchive,
                            openEnabled = catalogEnabled,
                            onCandidateSelected = { entry, candidate ->
                                if (model.state.value.operation == null) {
                                    catalog.extract(entry, candidate, archiveSourceUrl, catalogOrigin)
                                }
                            },
                        )
                    }
                } else if (tab.isCatalog && browserSite != null) {
                    val browserOrigin = catalogOrigin
                    savedContent.SaveableStateProvider("catalog-browser") {
                        SiteBrowserScreen(
                            site = browserSite,
                            initialUrl = browserUrl ?: browserSite.startUrl,
                            onDownload = { download ->
                                if (browserOrigin == catalogOrigin && model.state.value.operation == null) {
                                    catalog.downloadFromBrowser(browserSite, download, browserOrigin)
                                }
                            },
                            onBack = ::closeBrowser,
                            downloadsEnabled = catalogEnabled,
                            restoreLastPage = true,
                        )
                    }
                } else savedContent.SaveableStateProvider(tab.name) {
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
                        FullPresetTab.BROWSE -> Box(Modifier.widthIn(max = 920.dp).fillMaxSize()) {
                            BrowseScreen(
                                client = catalog.printablesClient,
                                onBrowseSite = ::openBrowser,
                                onLibrary = null,
                                onBack = navigateBack,
                                onDownloadRequested = { print, file ->
                                    if (model.state.value.operation == null) {
                                        catalog.downloadPrintables(print, file, catalogOrigin)
                                    }
                                },
                                downloadsEnabled = catalogEnabled,
                                printablesState = catalog.browseState,
                                webSearchFallback = R.string.full_presets_catalog_search_website,
                                onDetailClosed = { catalogOrigin++ },
                            )
                        }
                        FullPresetTab.LIBRARY -> Box(Modifier.widthIn(max = 920.dp).fillMaxSize()) {
                            LibraryScreen(
                                store = catalog.store,
                                onOpen = { entry -> openEntry(entry, entry.provenance.pageUrl.takeIf(String::isNotBlank)) },
                                onBack = navigateBack,
                                onRetryFromSource = { url ->
                                    val site = ModelSites.forUrl(url)
                                    if (site != null) openBrowser(site, url)
                                    else if (!openExternalBrowser(context, url)) catalog.showFailure(R.string.library_failed)
                                },
                                openEnabled = catalogEnabled,
                                listingRevision = catalogState.libraryRevision,
                                title = R.string.full_presets_library_tab,
                            )
                        }
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
    state.printReview?.let { review ->
        FullPresetPrintReviewDialog(
            review = review,
            printInProgress = state.operation == FullPresetOperation.PRINTING,
            onDismiss = model::closePrintReview,
            onRefresh = model::refreshPrintReview,
            onConfirm = model::confirmPrint,
            onPrintSetup = {
                model.closePrintReview()
                settingsOpen = true
            },
        )
    }
}

@Composable
private fun FullPresetBottomNavigation(selected: FullPresetTab, onSelect: (FullPresetTab) -> Unit) {
    val labels = FullPresetTab.entries.associateWith { stringResource(it.label) }
    val widest = widestNavigationLabel(labels.values)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
        val useTwoRows = widest + 4.dp > maxWidth / labels.size
        val columns = if (useTwoRows) 2 else labels.size
        Column(Modifier.selectableGroup()) {
            labels.keys.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { tab ->
                        FullPresetNavigationItem(
                            tab = tab,
                            label = labels.getValue(tab),
                            selected = tab == selected,
                            onClick = { onSelect(tab) },
                            modifier = Modifier.weight(1f).heightIn(min = if (useTwoRows) 48.dp else 64.dp),
                            horizontal = useTwoRows,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FullPresetNavigationRail(selected: FullPresetTab, onSelect: (FullPresetTab) -> Unit) {
    val labels = FullPresetTab.entries.associateWith { stringResource(it.label) }
    val width = (widestNavigationLabel(labels.values) + 12.dp).coerceIn(72.dp, 120.dp)
    Box(Modifier.fillMaxHeight().background(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.width(width).verticalScroll(rememberScrollState()).selectableGroup()) {
            labels.forEach { (tab, label) ->
                FullPresetNavigationItem(
                    tab = tab,
                    label = label,
                    selected = tab == selected,
                    onClick = { onSelect(tab) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    compact = true,
                )
            }
        }
    }
}

@Composable
private fun widestNavigationLabel(labels: Collection<String>): Dp {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelMedium
    val width = labels.maxOf {
        measurer.measure(AnnotatedString(it), style = style, softWrap = false, maxLines = 1).size.width
    }
    return with(LocalDensity.current) { width.toDp() }
}

@Composable
private fun FullPresetNavigationItem(
    tab: FullPresetTab,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    horizontal: Boolean = false,
    compact: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val itemModifier = modifier.testTag(tab.tag)
        .selectable(selected = selected, onClick = onClick, role = Role.Tab)
        .padding(horizontal = 2.dp)
    val icon: @Composable () -> Unit = {
        Box(
            modifier = Modifier.width(if (horizontal) 32.dp else 56.dp)
                .height(if (compact) 25.dp else 32.dp)
                .background(if (selected) colors.primaryContainer else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                tab.icon,
                contentDescription = null,
                modifier = Modifier.size(if (compact) 21.dp else 24.dp),
                tint = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
            )
        }
    }
    val text: @Composable () -> Unit = {
        // Match the paragraph layout used to measure the available navigation labels.
        Text(
            AnnotatedString(label),
            modifier = Modifier.testTag("${tab.tag}-label"),
            color = if (selected) colors.primary else colors.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
        )
    }
    if (horizontal) {
        Row(
            itemModifier,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            text()
        }
    } else {
        Column(
            itemModifier,
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            icon()
            text()
        }
    }
}
