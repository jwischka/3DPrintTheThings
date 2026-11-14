package com.u1.slicer.library

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.compose.ui.platform.LocalLifecycleOwner
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals

@RunWith(AndroidJUnit4::class)
class LibraryScreenInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun searchSurvivesSavedStateRestorationAndReopensStoredModels() {
        val files = Files.createTempDirectory("library-screen").toFile()
        try {
            val store = DownloadStore(files, OkHttpClient())
            runBlocking {
                val blue = File(files, "blue.stl").apply { writeText("solid blue\nendsolid blue") }
                val red = File(files, "red.stl").apply { writeText("solid red\nendsolid red") }
                store.importFile(blue, "blue.stl", DownloadProvenance("local", "", "Blue Model"))
                store.importFile(red, "red.stl", DownloadProvenance("local", "", "Red Model"))
            }
            assertEquals(setOf("Blue Model", "Red Model"),
                runBlocking { store.listing().entries.map { it.provenance.title }.toSet() })
            val restoration = StateRestorationTester(compose)
            restoration.setContent { LibraryScreen(store, onOpen = {}, onBack = {}) }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Blue Model").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Search library").performTextInput("Blue")
            compose.onNodeWithText("Name").performClick()
            compose.onNodeWithText("Name").assertIsSelected()
            compose.onNodeWithText("Blue Model").assertExists()
            compose.onNodeWithText("Red Model").assertDoesNotExist()

            restoration.emulateSavedInstanceStateRestore()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Blue Model").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Red Model").assertDoesNotExist()
            compose.onNodeWithText("Blue").assertExists()
            compose.onNodeWithText("Name").assertIsSelected()
        } finally { files.deleteRecursively() }
    }

    @Test fun resumeRefreshesEntriesAddedWhileScreenIsBackgrounded() {
        val files = Files.createTempDirectory("library-resume").toFile()
        try {
            val store = DownloadStore(files, OkHttpClient())
            val first = File(files, "first.stl").apply { writeText("solid first\nendsolid first") }
            runBlocking { store.importFile(first, "first.stl", DownloadProvenance("local", "", "First Model")) }
            assertEquals(listOf("First Model"),
                runBlocking { store.listing().entries.map { it.provenance.title } })
            val owner = object : LifecycleOwner {
                val registry = LifecycleRegistry(this)
                override val lifecycle: Lifecycle = registry
            }
            compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
            compose.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    LibraryScreen(store, onOpen = {}, onBack = {})
                }
            }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("First Model").fetchSemanticsNodes().isNotEmpty() }
            val second = File(files, "second.stl").apply { writeText("solid second\nendsolid second") }
            runBlocking { store.importFile(second, "second.stl", DownloadProvenance("local", "", "Second Model")) }
            compose.onNodeWithText("Second Model").assertDoesNotExist()
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Second Model").fetchSemanticsNodes().isNotEmpty() }
        } finally { files.deleteRecursively() }
    }

    @Test fun missingModelAfterReopenOffersDurableSourcePage() {
        val files = Files.createTempDirectory("library-recover-ui").toFile()
        try {
            val store = DownloadStore(files, OkHttpClient())
            val source = File(files, "source.stl").apply { writeText("solid lost\nendsolid lost") }
            val entry = runBlocking {
                store.importFile(source, "lost.stl",
                    DownloadProvenance("printables", "https://www.printables.com/model/42", "Lost Model"))
            }
            store.fileFor(entry).delete()
            val reopened = DownloadStore(files, OkHttpClient())
            assertEquals(listOf("Lost Model"),
                runBlocking { reopened.recoverableDownloads().map { it.provenance.title } })
            var retried: String? = null
            compose.setContent {
                LibraryScreen(reopened, onOpen = {}, onBack = {}, onRetryFromSource = { retried = it })
            }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Lost Model").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Open").assertDoesNotExist()
            compose.onNodeWithText("Find download again").performClick()
            compose.runOnIdle { assertEquals("https://www.printables.com/model/42", retried) }
        } finally { files.deleteRecursively() }
    }
}
