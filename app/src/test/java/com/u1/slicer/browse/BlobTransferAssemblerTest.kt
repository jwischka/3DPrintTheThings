package com.u1.slicer.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

class BlobTransferAssemblerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun orderedChunksProduceExactFileAndTransferOwnership() {
        val assembler = BlobTransferAssembler(temporaryFolder.root, maxBytes = 8)
        assertTrue(assembler.begin("nonce", 6))
        assertTrue(assembler.append("nonce", 0, encode("abc")))
        assertTrue(assembler.append("nonce", 1, encode("def")))
        val file = assembler.complete("nonce")!!
        assertEquals("abcdef", file.readText())
        assembler.reset()
        assertTrue(file.exists())
        file.delete()
    }

    @Test fun rejectsOversizeAndOutOfOrderChunksWithoutLeavingTempFiles() {
        val assembler = BlobTransferAssembler(temporaryFolder.root, maxBytes = 4)
        assertFalse(assembler.begin("nonce", 5))
        assertTrue(assembler.begin("nonce", 4))
        assertFalse(assembler.append("nonce", 1, encode("ab")))
        assertNull(assembler.complete("nonce"))
        assertTrue(temporaryFolder.root.listFiles().orEmpty().isEmpty())

        assertTrue(assembler.begin("nonce", 4))
        assertFalse(assembler.append("nonce", 0, encode("abcde")))
        assertTrue(temporaryFolder.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun wrongNonceCannotAlterActiveTransferAndIncompleteFinishCleansUp() {
        val assembler = BlobTransferAssembler(temporaryFolder.root, maxBytes = 8)
        assertTrue(assembler.begin("current", 4))
        assertFalse(assembler.append("old", 0, encode("ab")))
        assertTrue(assembler.append("current", 0, encode("ab")))
        assertNull(assembler.complete("old"))
        assertNull(assembler.complete("current"))
        assertTrue(temporaryFolder.root.listFiles().orEmpty().isEmpty())
    }

    private fun encode(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())
}
