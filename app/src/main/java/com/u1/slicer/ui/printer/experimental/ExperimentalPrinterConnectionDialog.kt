package com.u1.slicer.ui.printer.experimental

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.u1.slicer.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

@Composable
internal fun ExperimentalPrinterConnectionDialog(
    settings: ExperimentalPrinterConnection,
    connection: PrinterConnection,
    onDismiss: () -> Unit,
    onSaved: (PrinterConnection) -> Unit,
) {
    var endpoint by rememberSaveable { mutableStateOf(connection.endpoint) }
    var apiKey by remember { mutableStateOf("") }
    var clearKey by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val normalized = remember(endpoint) { ExperimentalPrinterConnection.validateEndpoint(endpoint) }
    val validKey = ExperimentalPrinterConnection.validKey(apiKey)
    val hasSavedKey = connection.hasApiKey && normalized == connection.endpoint
    val dismiss by rememberUpdatedState(onDismiss)

    fun saveConnection() {
        saving = true
        saveFailed = false
        val key = apiKey
        val clear = hasSavedKey && clearKey
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    settings.save(normalized, key, clear)
                } catch (_: IOException) {
                    null
                } catch (_: SecurityException) {
                    null
                }
            }
            saving = false
            if (result == null) saveFailed = true else onSaved(result)
        }
    }

    Dialog(
        onDismissRequest = { if (!saving) dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.matchParentSize().pointerInput(saving) {
                detectTapGestures { if (!saving) dismiss() }
            })
            Box(
                modifier = Modifier.matchParentSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    tonalElevation = 6.dp,
                ) {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.experimental_printer_connection_dialog),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.semantics { heading() },
                        )
                        OutlinedTextField(
                            value = endpoint,
                            onValueChange = { endpoint = it },
                            label = { Text(stringResource(R.string.experimental_printer_endpoint)) },
                            placeholder = { Text(stringResource(R.string.experimental_printer_endpoint_example)) },
                            singleLine = true,
                            enabled = !saving,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Next,
                            ),
                            isError = endpoint.isNotBlank() && normalized.isEmpty(),
                            modifier = Modifier.fillMaxWidth().testTag("experimental-printer-endpoint"),
                        )
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text(stringResource(R.string.experimental_printer_key)) },
                            singleLine = true,
                            enabled = !saving && !(hasSavedKey && clearKey),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                            visualTransformation = PasswordVisualTransformation(),
                            isError = !validKey,
                            modifier = Modifier.fillMaxWidth().testTag("experimental-printer-api-key"),
                        )
                        if (hasSavedKey && apiKey.isBlank()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .toggleable(
                                        value = clearKey,
                                        enabled = !saving,
                                        role = Role.Checkbox,
                                        onValueChange = { clearKey = it },
                                    )
                                    .testTag("experimental-printer-clear-key"),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = clearKey, onCheckedChange = null, enabled = !saving)
                                Text(stringResource(R.string.experimental_printer_clear_key))
                            }
                        }
                        if (saveFailed) {
                            Text(
                                stringResource(R.string.experimental_printer_save_failed),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Row(
                            modifier = Modifier.align(Alignment.End),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(
                                onClick = dismiss,
                                enabled = !saving,
                                modifier = Modifier.testTag("experimental-printer-cancel"),
                            ) {
                                Text(stringResource(R.string.experimental_printer_cancel))
                            }
                            Button(
                                enabled = normalized.isNotEmpty() && validKey && !saving,
                                modifier = Modifier.testTag("experimental-printer-save"),
                                onClick = ::saveConnection,
                            ) {
                                Text(stringResource(R.string.experimental_printer_save))
                            }
                        }
                    }
                }
            }
        }
    }
}
