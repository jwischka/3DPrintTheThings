package com.u1.slicer.ui.printer

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.R
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrinterKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** UI-only tests: no printer connection, native engine, or Activity from the main app. */
@RunWith(AndroidJUnit4::class)
class PrinterSettingsInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val printer = Printer("printer-id", "Fixture", PrinterKind.MOONRAKER, "http://printer.test")
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun savedKeyIsNeverPrefilledAndBlankSaveKeepsIt() {
        val saved = AtomicReference<String?>()
        val calls = AtomicInteger()
        compose.setContent {
            PrinterEditDialog(existing = printer,
                hasSavedApiKey = { _, _ -> true },
                onSave = { _, _, _, _, _, _, _, key -> saved.set(key); calls.incrementAndGet() },
                onTest = { _, _, _, _, _, _, _ -> null }, onDismiss = {})
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text(R.string.moonraker_key_remove)).fetchSemanticsNodes().isNotEmpty() }
        val keyField = compose.onNodeWithText(text(R.string.moonraker_api_key)).fetchSemanticsNode()
        assertEquals("", keyField.config[SemanticsProperties.EditableText].text)
        compose.onNodeWithText("saved-secret").assertDoesNotExist()
        compose.onNodeWithText("Save").performClick()
        assertEquals(1, calls.get())
        assertNull(saved.get())
    }

    @Test fun explicitRemovalSavesEmptyKey() {
        val saved = AtomicReference<String?>()
        val calls = AtomicInteger()
        compose.setContent {
            PrinterEditDialog(existing = printer, hasSavedApiKey = { _, _ -> true },
                onSave = { _, _, _, _, _, _, _, key -> saved.set(key); calls.incrementAndGet() },
                onTest = { _, _, _, _, _, _, _ -> null }, onDismiss = {})
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text(R.string.moonraker_key_remove)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text(R.string.moonraker_key_remove)).performScrollTo().performClick()
        compose.onNodeWithText("Save").performClick()
        assertEquals(1, calls.get())
        assertEquals("", saved.get())
    }

    @Test fun enteredKeyIsPassedToTestAndSave() {
        val tested = AtomicReference<String?>()
        val saved = AtomicReference<String?>()
        val testCalls = AtomicInteger()
        val saveCalls = AtomicInteger()
        compose.setContent {
            PrinterEditDialog(existing = printer, hasSavedApiKey = { _, _ -> true },
                onSave = { _, _, _, _, _, _, _, key -> saved.set(key); saveCalls.incrementAndGet() },
                onTest = { _, _, _, _, _, _, key -> tested.set(key); testCalls.incrementAndGet(); null },
                onDismiss = {})
        }
        compose.onNodeWithText(text(R.string.moonraker_api_key)).performScrollTo().performTextInput("draft-key")
        compose.onNodeWithText("Test connection").performScrollTo().performClick()
        compose.waitUntil(10_000) { testCalls.get() == 1 }
        assertEquals("draft-key", tested.get())
        compose.onNodeWithText("Save").performClick()
        assertEquals(1, saveCalls.get())
        assertEquals("draft-key", saved.get())
    }

    @Test fun cancelPrintRequiresExplicitConfirmation() {
        val visible = mutableStateOf(true)
        val confirmed = AtomicInteger()
        val dismissed = AtomicInteger()
        compose.setContent {
            if (visible.value) CancelPrintDialog("Fixture",
                onConfirm = { confirmed.incrementAndGet(); visible.value = false },
                onDismiss = { dismissed.incrementAndGet(); visible.value = false })
        }
        assertEquals(0, confirmed.get())
        compose.onNodeWithText(text(R.string.printer_cancel_print_dismiss)).performClick()
        assertEquals(0, confirmed.get())
        assertEquals(1, dismissed.get())
        compose.runOnUiThread { visible.value = true }
        assertEquals(0, confirmed.get())
        compose.onNodeWithText(text(R.string.printer_cancel_print_confirm)).performClick()
        assertEquals(1, confirmed.get())
    }
}
