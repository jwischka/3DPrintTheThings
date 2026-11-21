package com.u1.slicer.presets.preview

import com.u1.slicer.viewer.MeshData
import com.u1.slicer.viewer.NativeRenderBatch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun assignsColorsInTheCopiedGeometryWithoutChangingMaterialIdsOrCoordinates() {
        val palette = listOf(floatArrayOf(0.1f, 0.2f, 0.3f, 1f), floatArrayOf(0.6f, 0.7f, 0.8f, 1f))
        val first = batch(0, 1)
        val second = batch(0, 0)
        val mesh = MeshData(listOf(first, second), 0f, 0f, 0f, 1f, 1f, 1f, modifierBlockStartTriangle = 3)

        mesh.colorFullPresetPreview(palette) {}

        assertTrue(mesh.hasPerVertexColor)
        assertBatch(first, listOf(palette[0], palette[1]), 0, 1)
        assertBatch(second, listOf(palette[0], MeshData.MODIFIER_PREVIEW_COLOR), 0, 0)
    }

    @Test
    fun previewBudgetHandlesExpandedPaintAndRejectsOversizedSnapshots() {
        assertFalse(canCopyFullPresetPreview(0))
        assertTrue(canCopyFullPresetPreview(675_000))
        assertTrue(canCopyFullPresetPreview(700_000))
        assertFalse(canCopyFullPresetPreview(700_001))
    }

    private fun batch(vararg slots: Int): NativeRenderBatch {
        val floats = slots.size * 3 * MeshData.FLOATS_PER_VERTEX
        val geometry = ByteBuffer.allocateDirect(floats * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { for (i in 0 until floats) put(i, i.toFloat()) }
        val materials = ByteBuffer.allocateDirect(slots.size).apply {
            slots.forEachIndexed { i, slot -> put(i, slot.toByte()) }
        }
        return NativeRenderBatch(geometry, materials, slots.size)
    }

    private fun assertBatch(batch: NativeRenderBatch, colors: List<FloatArray>, vararg slots: Int) {
        assertNull(batch.colorBuffer)
        val materials = requireNotNull(batch.materialIndices)
        for (triangle in 0 until batch.triangleCount) {
            assertEquals(slots[triangle], materials.get(triangle).toInt())
            for (vertex in 0 until 3) {
                val start = (triangle * 3 + vertex) * MeshData.FLOATS_PER_VERTEX
                val geometry = batch.geometry
                for (i in 0 until 6) assertEquals((start + i).toFloat(), geometry.get(start + i), 0f)
                val rgba = FloatArray(4) { geometry.get(start + 6 + it) }
                assertArrayEquals(colors[triangle], rgba, 0f)
            }
        }
        assertEquals(0, batch.geometry.position())
        assertEquals(0, materials.position())
    }
}
