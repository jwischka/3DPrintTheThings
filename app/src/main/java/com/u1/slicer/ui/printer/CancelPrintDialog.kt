package com.u1.slicer.ui.printer

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.u1.slicer.R

@Composable
fun CancelPrintDialog(
    printerName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.printer_cancel_print_title)) },
        text = { Text(stringResource(R.string.printer_cancel_print_message, printerName)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.printer_cancel_print_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.printer_cancel_print_dismiss))
            }
        },
    )
}
