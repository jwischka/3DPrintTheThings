package com.u1.slicer.ui.printer.experimental

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.u1.slicer.R
import com.u1.slicer.network.MoonrakerClient
import com.u1.slicer.network.MoonrakerHeaterReading
import com.u1.slicer.network.MoonrakerJsonRead
import com.u1.slicer.network.MoonrakerStatusResult
import com.u1.slicer.network.MoonrakerStatusSnapshot
import com.u1.slicer.network.U1FilamentResult
import com.u1.slicer.network.U1FilamentSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Date
import kotlin.math.ceil

private const val CAMERA_ASPECT_RATIO = 16f / 9f

@Composable
fun ExperimentalPrinterDashboard(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val settings = remember(context) { ExperimentalPrinterConnection(context) }
    var connection by remember(settings) { mutableStateOf<PrinterConnection?>(null) }
    var editConnection by rememberSaveable { mutableStateOf(false) }
    var configurationVersion by rememberSaveable { mutableStateOf(0) }

    LaunchedEffect(settings) {
        connection = withContext(Dispatchers.IO) { settings.load() }
    }

    val configured = connection
    BoxWithConstraints(modifier) {
        val cameraMaxWidth = (maxHeight - 24.dp).coerceAtLeast(1.dp) * CAMERA_ASPECT_RATIO
        Column(
            modifier = Modifier.fillMaxSize()
                .testTag("experimental-printer-dashboard")
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when {
                configured == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                configured.endpoint.isEmpty() -> DashboardCard {
                    Text(
                        stringResource(R.string.experimental_printer_connect_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(stringResource(R.string.experimental_printer_connect_description))
                    Button(
                        onClick = { editConnection = true },
                        modifier = Modifier.testTag("experimental-printer-connect"),
                    ) {
                        Text(stringResource(R.string.experimental_printer_connect))
                    }
                }
                else -> ConnectedPrinter(settings, configured, configurationVersion, cameraMaxWidth) {
                    editConnection = true
                }
            }
        }
    }
    if (editConnection && configured != null) {
        ExperimentalPrinterConnectionDialog(settings, configured, onDismiss = { editConnection = false }) {
            connection = it
            configurationVersion++
            editConnection = false
        }
    }
}

private data class StatusReading(val snapshot: MoonrakerStatusSnapshot, val receivedAt: Long, val remainingSeconds: Double?)

@Composable
private fun ConnectedPrinter(
    settings: ExperimentalPrinterConnection,
    connection: PrinterConnection,
    configurationVersion: Int,
    cameraMaxWidth: Dp,
    onEdit: () -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var latest by remember(connection, configurationVersion) { mutableStateOf<StatusReading?>(null) }
    var failure by remember(connection, configurationVersion) { mutableStateOf<MoonrakerStatusResult.Unavailable?>(null) }
    var refreshing by remember(connection, configurationVersion) { mutableStateOf(true) }
    var filaments by remember(connection, configurationVersion) { mutableStateOf<U1FilamentSnapshot?>(null) }
    var filamentsStale by remember(connection, configurationVersion) { mutableStateOf(false) }
    var client by remember(connection, configurationVersion) { mutableStateOf<MoonrakerClient?>(null) }
    var retryVersion by remember { mutableIntStateOf(0) }

    LaunchedEffect(settings, connection, configurationVersion) {
        client = withContext(Dispatchers.IO) { settings.clientIfCurrent(connection) }
    }
    LaunchedEffect(client, lifecycle, retryVersion) {
        val printer = client ?: return@LaunchedEffect
        val printTime = ExperimentalPrintTimeReader(printer::readEstimatedPrintSeconds)
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            refreshing = true
            while (isActive) {
                val result = printer.readStatusSnapshot()
                when (result) {
                    is MoonrakerStatusResult.Received -> {
                        val receivedAt = System.currentTimeMillis()
                        val remaining = printTime.remainingSeconds(result.snapshot)
                        latest = StatusReading(result.snapshot, receivedAt, remaining)
                        failure = null
                    }
                    is MoonrakerStatusResult.Unavailable -> failure = result
                }
                refreshing = false
                delay(if (failure == null) 2_000L else 8_000L)
            }
        }
    }
    LaunchedEffect(client, lifecycle, retryVersion) {
        val printer = client ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            filamentsStale = filaments?.isFresh(SystemClock.elapsedRealtime()) == false
            while (isActive) {
                when (val result = printer.readU1FilamentSnapshot()) {
                    is U1FilamentResult.Received -> {
                        filaments = result.snapshot
                        filamentsStale = false
                    }
                    is U1FilamentResult.Unavailable -> filamentsStale = true
                }
                delay(5_000)
            }
        }
    }

    val online = !refreshing && failure == null && latest != null
    DashboardCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                val problem = failure
                val title = when {
                    refreshing -> R.string.experimental_printer_refreshing
                    problem?.problem == MoonrakerJsonRead.Problem.UNREACHABLE -> R.string.experimental_printer_offline
                    problem?.problem == MoonrakerJsonRead.Problem.AUTHORIZATION -> R.string.experimental_printer_auth_required
                    problem != null -> R.string.experimental_printer_status_unavailable
                    else -> R.string.experimental_printer_connected
                }
                Text(
                    stringResource(title),
                    style = MaterialTheme.typography.titleLarge,
                    color = if (problem != null && !refreshing) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("experimental-printer-connection-state"),
                )
                Text(
                    connection.endpoint,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            OutlinedButton(onClick = onEdit, modifier = Modifier.testTag("experimental-printer-edit")) {
                Text(stringResource(R.string.experimental_printer_edit))
            }
        }
        val problem = failure
        if (problem != null && !refreshing) {
            val description = when (problem.problem) {
                MoonrakerJsonRead.Problem.AUTHORIZATION -> stringResource(R.string.experimental_printer_auth_hint)
                MoonrakerJsonRead.Problem.HTTP -> stringResource(R.string.experimental_printer_http, problem.httpCode ?: 0)
                MoonrakerJsonRead.Problem.REDIRECT -> stringResource(R.string.experimental_printer_redirect)
                MoonrakerJsonRead.Problem.UNREACHABLE -> stringResource(R.string.experimental_printer_offline_hint)
                else -> stringResource(R.string.experimental_printer_response_error)
            }
            Text(description, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(
                onClick = {
                    refreshing = true
                    retryVersion++
                },
            ) {
                Text(stringResource(R.string.experimental_printer_retry))
            }
        }
        latest?.let {
            val label = if (online) R.string.experimental_printer_updated else R.string.experimental_printer_stale
            Text(stringResource(label, timeOfDay(it.receivedAt)), style = MaterialTheme.typography.bodySmall)
        }
    }

    val reading = latest
    val staleAt = reading?.receivedAt?.takeUnless { online }
    reading?.let { JobStatusCard(it, staleAt) }
    CameraCard(client, online, cameraMaxWidth, connection.endpoint, configurationVersion)
    reading?.let { TemperaturesCard(it.snapshot, staleAt) }
    filaments?.let { LoadedFilamentsCard(it, filamentsStale || !it.isFresh(SystemClock.elapsedRealtime())) }
}

@Composable
private fun LoadedFilamentsCard(snapshot: U1FilamentSnapshot, stale: Boolean) {
    DashboardCard {
        Text(
            stringResource(R.string.experimental_printer_filaments),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.testTag("experimental-printer-filaments"),
        )
        Text(
            stringResource(
                if (stale) R.string.experimental_printer_filaments_stale else R.string.experimental_printer_filaments_received,
                timeOfDay(snapshot.receivedAtMillis),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        snapshot.slots.forEach { slot ->
            val swatch = slot.color?.takeIf { slot.present == true }
            val swatchDescription = swatch?.let { stringResource(R.string.experimental_printer_filament_color, it) }
            val details = listOfNotNull(slot.vendor, slot.material, slot.subtype).joinToString(" · ")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(24.dp)
                        .background(
                            if (swatch == null) MaterialTheme.colorScheme.surfaceVariant
                            else Color(android.graphics.Color.parseColor(swatch)),
                            CircleShape,
                        )
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        .semantics { swatchDescription?.let { contentDescription = it } },
                )
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.experimental_printer_filament_slot, slot.physicalSlot))
                    if (slot.present == true && details.isNotEmpty()) {
                        Text(details, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    stringResource(when (slot.present) {
                        true -> R.string.experimental_printer_filament_loaded
                        false -> R.string.experimental_printer_filament_empty
                        null -> R.string.experimental_printer_filament_unknown
                    }),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun JobStatusCard(reading: StatusReading, staleAt: Long?) {
    val snapshot = reading.snapshot
    DashboardCard {
        val readiness = snapshot.klippyState
        val state = snapshot.printState
        val title = when {
            readiness == "startup" -> R.string.experimental_printer_starting
            readiness == "shutdown" -> R.string.experimental_printer_shutdown
            readiness == "error" -> R.string.experimental_printer_firmware_error
            readiness == "disconnected" -> R.string.experimental_printer_firmware_disconnected
            readiness != null && readiness != "ready" -> R.string.experimental_printer_job_unknown
            state == "printing" -> R.string.experimental_printer_printing
            state == "paused" -> R.string.experimental_printer_paused
            state == "complete" -> R.string.experimental_printer_complete
            state == "error" -> R.string.experimental_printer_job_error
            state == "cancelled" || state == "canceled" -> R.string.experimental_printer_cancelled
            state == "standby" || (state == null && readiness == "ready") -> R.string.experimental_printer_ready
            else -> R.string.experimental_printer_job_unknown
        }
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
        staleAt?.let { StaleStatusCaption(it) }
        snapshot.reportedReason?.let { reason ->
            val label = if (staleAt == null) R.string.experimental_printer_reported_reason
            else R.string.experimental_printer_stale_reason
            Text(
                stringResource(label, reason),
                modifier = Modifier.testTag("experimental-printer-reported-reason"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if ((readiness == null || readiness == "ready") && state != "standby") {
            snapshot.filename?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            val progress = snapshot.progress
            if (progress != null) {
                val percent = NumberFormat.getPercentInstance().format(progress.toDouble())
                Text(stringResource(R.string.experimental_printer_progress, percent))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            (snapshot.elapsedSeconds ?: snapshot.activeSeconds)?.let {
                val label = if (snapshot.elapsedSeconds != null) R.string.experimental_printer_elapsed
                else R.string.experimental_printer_print_time
                Text(stringResource(label, duration(it)))
            }
            if (state == "printing" || state == "paused") {
                reading.remainingSeconds?.let { remaining ->
                    val minutes = ceil(remaining / 60).toInt().coerceAtLeast(1)
                    Text(
                        pluralStringResource(R.plurals.experimental_printer_remaining_minutes, minutes, minutes),
                        modifier = Modifier.testTag("experimental-printer-remaining"),
                    )
                    if (state == "printing" && staleAt == null) {
                        val finish = reading.receivedAt + (remaining * 1000).toLong()
                        val time = if (DateUtils.isToday(finish)) timeOfDay(finish)
                        else DateUtils.formatDateTime(LocalContext.current, finish,
                            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)
                        Text(
                            stringResource(R.string.experimental_printer_estimated_finish, time),
                            modifier = Modifier.testTag("experimental-printer-estimated-finish"),
                        )
                    }
                }
            }
            val current = snapshot.currentLayer
            val total = snapshot.totalLayers
            if (current != null && total != null && total > 0 && current <= total) {
                Text(stringResource(R.string.experimental_printer_layers, current, total))
            }
        }
    }
}

@Composable
private fun TemperaturesCard(snapshot: MoonrakerStatusSnapshot, staleAt: Long?) {
    if (snapshot.bed == null && snapshot.extruders.isEmpty()) return
    DashboardCard {
        Text(stringResource(R.string.experimental_printer_temperatures), style = MaterialTheme.typography.titleLarge)
        staleAt?.let { StaleStatusCaption(it) }
        snapshot.bed?.let {
            TemperatureRow(stringResource(R.string.experimental_printer_bed), it)
        }
        snapshot.extruders.forEach { (index, heater) ->
            val extruder = if (index == 0) "extruder" else "extruder$index"
            val label = if (extruder == snapshot.activeExtruder) R.string.experimental_printer_active_nozzle
            else R.string.experimental_printer_nozzle
            TemperatureRow(stringResource(label, index + 1), heater)
        }
    }
}

@Composable
private fun StaleStatusCaption(receivedAt: Long) {
    Text(
        stringResource(R.string.experimental_printer_stale_card, timeOfDay(receivedAt)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TemperatureRow(label: String, heater: MoonrakerHeaterReading) {
    val temperature = heater.temperature
    val target = heater.target
    val value = when {
        temperature != null && target != null -> stringResource(
            R.string.experimental_printer_temperature_target, temperature, target,
        )
        temperature != null -> stringResource(R.string.experimental_printer_temperature, temperature)
        target != null -> stringResource(R.string.experimental_printer_target_only, target)
        else -> stringResource(R.string.experimental_printer_no_temperature)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f))
        Text(value)
    }
}

private data class CameraFrame(
    val bitmap: Bitmap,
    val receivedAt: Long,
    val isStoredMonitorFile: Boolean,
)

@Composable
private fun CameraCard(
    client: MoonrakerClient?,
    online: Boolean,
    maxWidth: Dp,
    endpoint: String,
    configurationVersion: Int,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var frame by remember(client) { mutableStateOf<CameraFrame?>(null) }
    var pending by remember(client) { mutableStateOf(false) }
    var unavailable by remember(client) { mutableStateOf(false) }
    var expanded by rememberSaveable(endpoint, configurationVersion) { mutableStateOf(false) }

    LaunchedEffect(client, lifecycle, online) {
        if (client == null || !online) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            pending = true
            val camera = ExperimentalCameraReader(client)
            while (isActive) {
                val snapshot = camera.nextFrame { bytes -> withContext(Dispatchers.Default) { decodeSnapshot(bytes) } }
                if (snapshot != null) {
                    frame = CameraFrame(snapshot.image, System.currentTimeMillis(), snapshot.isStoredMonitorFile)
                }
                unavailable = snapshot == null
                pending = false
                delay(if (unavailable) 20_000L else 5_000L)
            }
        }
    }

    val latest = frame
    val image = remember(latest?.bitmap) { latest?.bitmap?.asImageBitmap() }
    val caption = latest?.let {
        val label = if (online && !unavailable && !pending) R.string.experimental_printer_snapshot_received
        else R.string.experimental_printer_snapshot_stale
        stringResource(label, timeOfDay(it.receivedAt))
    }
    val cameraStatus = stringResource(
        when {
            !online -> R.string.experimental_printer_camera_offline
            unavailable && !pending -> R.string.experimental_printer_camera_unavailable
            else -> R.string.experimental_printer_camera_loading
        },
    )
    DashboardCard {
        Text(stringResource(R.string.experimental_printer_camera), style = MaterialTheme.typography.titleLarge)
        if (image != null && caption != null && latest != null) {
            val expandLabel = stringResource(R.string.experimental_printer_expand_camera)
            Box(
                modifier = Modifier.align(Alignment.CenterHorizontally)
                    .widthIn(max = maxWidth).fillMaxWidth().aspectRatio(CAMERA_ASPECT_RATIO)
                    .clickable(role = Role.Button, onClickLabel = expandLabel) { expanded = true }
                    .testTag("experimental-printer-camera-frame"),
            ) {
                Image(
                    bitmap = image,
                    contentDescription = stringResource(R.string.experimental_printer_snapshot),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.matchParentSize(),
                )
                Icon(
                    Icons.Default.Fullscreen,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.65f), CircleShape).padding(6.dp),
                )
            }
            CameraSnapshotCaption(caption, latest.isStoredMonitorFile)
        } else {
            Text(cameraStatus)
        }
    }
    if (expanded) {
        ExpandedCamera(
            image = image,
            caption = caption,
            isStoredMonitorFile = latest?.isStoredMonitorFile == true,
            cameraStatus = cameraStatus,
            onClose = { expanded = false },
        )
    }
}

@Composable
private fun ExpandedCamera(
    image: ImageBitmap?,
    caption: String?,
    isStoredMonitorFile: Boolean,
    cameraStatus: String,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.experimental_printer_camera),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    IconButton(onClick = onClose, modifier = Modifier.testTag("experimental-printer-camera-close")) {
                        Icon(Icons.Default.Close, stringResource(R.string.experimental_printer_close_camera))
                    }
                }
                if (image != null && caption != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        CameraSnapshotCaption(
                            caption,
                            isStoredMonitorFile,
                            Modifier.testTag("experimental-printer-expanded-camera-caption"),
                        )
                    }
                    Image(
                        bitmap = image,
                        contentDescription = stringResource(R.string.experimental_printer_snapshot),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black)
                            .testTag("experimental-printer-camera-expanded"),
                    )
                } else {
                    Box(Modifier.fillMaxWidth().weight(1f).padding(16.dp), contentAlignment = Alignment.Center) {
                        Text(cameraStatus)
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraSnapshotCaption(caption: String, isStoredMonitorFile: Boolean, modifier: Modifier = Modifier) {
    Text(caption, modifier = modifier, style = MaterialTheme.typography.bodySmall)
    if (isStoredMonitorFile) {
        Text(
            stringResource(R.string.experimental_printer_snapshot_capture_unknown),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun decodeSnapshot(bytes: ByteArray): Bitmap? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    options.inJustDecodeBounds = false
    options.inSampleSize = 1
    val largestSide = maxOf(options.outWidth, options.outHeight)
    while (largestSide / options.inSampleSize > 1280) options.inSampleSize *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

@Composable
private fun DashboardCard(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun timeOfDay(millis: Long): String = DateFormat.getTimeFormat(LocalContext.current).format(Date(millis))

@Composable
private fun duration(seconds: Double): String {
    val elapsed = seconds.toLong()
    return when {
        elapsed < 60 -> stringResource(R.string.experimental_printer_duration_seconds, elapsed)
        elapsed < 3600 -> stringResource(R.string.experimental_printer_duration_minutes, elapsed / 60)
        else -> stringResource(R.string.experimental_printer_duration_hours, elapsed / 3600, elapsed % 3600 / 60)
    }
}
