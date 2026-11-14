package com.u1.slicer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.DataOutputStream
import java.security.MessageDigest

class MoonrakerApiKeyStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun persistsEndpointBoundKeysAndOnlyRevisesOnChanges() {
        val store = MoonrakerApiKeyStore(temp.root)
        store.put("printer", "http://printer.local:7125/proxy", "  test-key  ")
        assertEquals("test-key", store.get("printer", "http://printer.local:7125/proxy/"))
        assertEquals(mapOf("printer" to 1L), store.revisions.value)
        store.put("printer", "http://printer.local:7125/proxy", "test-key")
        assertEquals(mapOf("printer" to 1L), store.revisions.value)
        assertEquals("test-key", MoonrakerApiKeyStore(temp.root).get("printer", "http://printer.local:7125/proxy"))
        for (url in listOf("http://other.local:7125/proxy", "http://printer.local:7126/proxy", "http://printer.local:7125/other", "https://printer.local/proxy")) {
            assertNull(store.get("printer", url))
        }
        store.put("other", "printer.local", "different")
        store.remove("printer")
        store.remove("printer")
        assertEquals(mapOf("printer" to 2L, "other" to 1L), store.revisions.value)
        assertNull(store.get("printer", "http://printer.local:7125/proxy"))
        assertEquals("different", store.get("other", "http://printer.local:7125"))
    }

    @Test fun unsafeIdsAreHashedAndBlankClears() {
        val store = MoonrakerApiKeyStore(temp.root)
        val id = "../../unsafe/credential"
        store.put(id, "https://example.test/prefix", "secret")
        val root = File(temp.root, "moonraker-api-keys")
        val files = root.listFiles()!!
        assertEquals(1, files.size)
        assertTrue(files.single().name.matches(Regex("[0-9a-f]{64}\\.key")))
        assertFalse(files.single().name.contains("unsafe"))
        assertEquals("secret", store.get(id, "https://example.test/prefix"))
        store.put(id, "https://example.test/prefix", "  ")
        assertNull(store.get(id, "https://example.test/prefix"))
        assertEquals(2L, store.revisions.value[id] ?: -1L)
    }

    @Test fun corruptAndOversizedRecordsFailClosed() {
        val store = MoonrakerApiKeyStore(temp.root)
        store.put("id", "printer.local", "secret")
        val file = File(temp.root, "moonraker-api-keys").listFiles()!!.single()
        file.writeText("corrupt")
        assertNull(store.get("id", "printer.local"))
        file.writeBytes(ByteArray(9000))
        assertNull(store.get("id", "printer.local"))
        assertEquals(1L, store.revisions.value["id"] ?: -1L)
    }

    @Test fun invalidInputDoesNotCreateFilesOrExposeKey() {
        val store = MoonrakerApiKeyStore(temp.root)
        val secret = "private\nvalue"
        val error = assertThrows(IllegalArgumentException::class.java) { store.put("id", "printer.local", secret) }
        assertFalse(error.message.orEmpty().contains(secret))
        assertThrows(IllegalArgumentException::class.java) { store.put("id", "https://example.test/?token=x", "key") }
        assertThrows(IllegalArgumentException::class.java) { store.put("id", "printer.local", "x".repeat(1025)) }
        assertFalse(File(temp.root, "moonraker-api-keys").exists())
        assertTrue(store.revisions.value.isEmpty())
    }

    @Test fun nonAsciiAndHeaderControlsDoNotReplaceExistingKey() {
        val store = MoonrakerApiKeyStore(temp.root)
        store.put("id", "printer.local", "old-key")
        for (invalid in listOf("caf\u00e9", "secret\r\nInjected: value")) {
            assertThrows(IllegalArgumentException::class.java) { store.put("id", "printer.local", invalid) }
            assertEquals("old-key", store.get("id", "printer.local"))
            assertEquals(1L, store.revisions.value["id"] ?: -1L)
        }
        val file = File(temp.root, "moonraker-api-keys").listFiles()!!.single()
        for (invalid in listOf("caf\u00e9", "secret\r\nInjected: value")) {
            DataOutputStream(file.outputStream()).use { output ->
                output.writeInt(1)
                output.writeUTF("http://printer.local:7125")
                output.writeUTF(invalid)
            }
            assertNull(store.get("id", "printer.local"))
        }
    }

    @Test fun failedAtomicReplaceLeavesNoTemporaryOrRevision() {
        val store = MoonrakerApiKeyStore(temp.root)
        val root = File(temp.root, "moonraker-api-keys").apply { mkdirs() }
        val hash = MessageDigest.getInstance("SHA-256").digest("id".toByteArray()).joinToString("") { "%02x".format(it) }
        val blocker = File(root, "$hash.key").apply { mkdirs(); File(this, "child").writeText("keep") }
        val error = assertThrows(IOException::class.java) { store.put("id", "printer.local", "do-not-log") }
        assertFalse(error.message.orEmpty().contains("do-not-log"))
        assertTrue(File(blocker, "child").isFile)
        assertTrue(root.listFiles()!!.none { it.name.endsWith(".tmp") })
        assertTrue(store.revisions.value.isEmpty())
    }
}
