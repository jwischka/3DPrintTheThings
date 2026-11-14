package com.u1.slicer.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.u1.slicer.data.CanonicalFilamentList
import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.data.FilamentEntry
import com.u1.slicer.data.FilamentSource
import com.u1.slicer.network.FilamentSlot
import com.u1.slicer.u1.LiveSpoolState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveSpoolMappingInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private val canonical = CanonicalFilamentList(listOf(
        FilamentEntry(0, "#FF0000", "PLA", FilamentSource.FILE_COLOUR),
    ))
    private val presets = listOf(
        ExtruderPreset(0, "#FF0000", "PLA"),
        ExtruderPreset(1, "#0000FF", "PLA"),
    )
    private val ready = LiveSpoolState.Ready(listOf(
        FilamentSlot(0, "E1", "#FF0000", true, "PLA"),
        FilamentSlot(1, "E2", "#0000FF", true, "PLA"),
    ))

    @Test fun delayedReadyKeepsManualSelection() {
        val state = mutableStateOf<LiveSpoolState>(LiveSpoolState.Loading)
        var confirmed: List<Int>? = null
        compose.setContent {
            FilamentMappingDialog(canonical, presets, liveSpoolState = state.value,
                onConfirm = { confirmed = it }, onDismiss = {})
        }
        compose.onNodeWithText("Checking loaded spools…").assertExists()
        compose.onNodeWithText("E1 · PLA").performClick()
        compose.onNodeWithText("E2 · PLA").performClick()
        compose.runOnIdle { state.value = ready }
        compose.waitForIdle()
        compose.onNodeWithText("Send to Printer").performClick()
        compose.runOnIdle { assertEquals(listOf(1), confirmed) }
    }

    @Test fun delayedReadyKeepsProvidedInitialMapping() {
        val state = mutableStateOf<LiveSpoolState>(LiveSpoolState.Loading)
        var confirmed: List<Int>? = null
        compose.setContent {
            FilamentMappingDialog(canonical, presets, initialMapping = listOf(1),
                liveSpoolState = state.value, onConfirm = { confirmed = it }, onDismiss = {})
        }
        compose.runOnIdle { state.value = ready }
        compose.waitForIdle()
        compose.onNodeWithText("Send to Printer").performClick()
        compose.runOnIdle { assertEquals(listOf(1), confirmed) }
    }

    @Test fun delayedReadySuggestsOnlyWhenUntouched() {
        val state = mutableStateOf<LiveSpoolState>(LiveSpoolState.Loading)
        val swapped = LiveSpoolState.Ready(listOf(
            FilamentSlot(0, "E1", "#0000FF", true, "PLA"),
            FilamentSlot(1, "E2", "#FF0000", true, "PLA"),
        ))
        var confirmed: List<Int>? = null
        compose.setContent {
            FilamentMappingDialog(canonical, presets, liveSpoolState = state.value,
                onConfirm = { confirmed = it }, onDismiss = {})
        }
        compose.runOnIdle { state.value = swapped }
        compose.waitForIdle()
        compose.onNodeWithText("Send to Printer").performClick()
        compose.runOnIdle { assertEquals(listOf(1), confirmed) }
    }

    @Test fun unknownLiveSlotDoesNotClaimSavedPresetOrWarnMismatch() {
        val state = LiveSpoolState.Ready(emptyList())
        compose.setContent {
            FilamentMappingDialog(canonical, presets, liveSpoolState = state,
                sliceTimeMaterials = listOf("PETG"), onConfirm = {}, onDismiss = {})
        }
        compose.onNodeWithText("E1 · Unknown").assertExists()
        compose.onNodeWithText("Sliced as PETG, but E1 has PLA. Temperatures may not match.")
            .assertDoesNotExist()
    }

    @Test fun emptyAndUnavailableStatesWarnWithoutBlocking() {
        val state = mutableStateOf<LiveSpoolState>(LiveSpoolState.Ready(listOf(
            FilamentSlot(0, "E1", "#FF0000", false, ""),
        )))
        var confirmed: List<Int>? = null
        compose.setContent {
            FilamentMappingDialog(canonical, presets, liveSpoolState = state.value,
                sliceTimeMaterials = listOf("PLA"), onConfirm = { confirmed = it }, onDismiss = {})
        }
        compose.onNodeWithText("E1 is empty. Load filament before printing.").assertExists()
        compose.runOnIdle { state.value = LiveSpoolState.Unavailable }
        compose.onNodeWithText("Live spool information is unavailable. Check the slots on the printer.").assertExists()
        compose.onNodeWithText("Send to Printer").performClick()
        compose.runOnIdle { assertEquals(listOf(0), confirmed) }
    }
}
