package com.u1.slicer.presets.preview

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FullPresetPreviewBufferTest {
    @Test
    fun copiesSourceWindowIntoIndependentNativeOrderBuffer() = with(FullPresetPreviewLoader) {
        val source = ByteBuffer.allocateDirect(7).apply {
            put(byteArrayOf(9, 18, 42, 75, -12, 101, 108))
            position(2)
            limit(6)
        }

        val copy = requireNotNull(source.copyDirect(4))
        assertTrue(copy.isDirect)
        assertEquals(ByteOrder.nativeOrder(), copy.order())
        assertEquals(0, copy.position())
        assertEquals(4, copy.limit())
        assertEquals(2, source.position())
        assertEquals(6, source.limit())
        assertEquals(42.toByte(), source.get(2))

        source.put(2, 0)
        assertArrayEquals(byteArrayOf(42, 75, -12, 101), ByteArray(4).also { copy.get(it) })
        assertNull(source.copyDirect(3))
    }
}
