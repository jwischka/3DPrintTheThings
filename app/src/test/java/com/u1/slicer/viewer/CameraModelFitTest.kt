package com.u1.slicer.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraModelFitTest {
    @Test
    fun fitsTranslatedModelInPortraitViewport() {
        val mesh = MeshData(emptyList(), 75f, 120f, 0f, 105f, 140f, 10f)
        val camera = Camera().apply {
            panX = 30.0
            panY = -20.0
        }
        camera.fitModel(mesh, 200, 200)
        val squareDistance = camera.distance
        camera.fitModel(mesh, 100, 200)

        assertEquals(90.0, camera.targetX, 0.0)
        assertEquals(130.0, camera.targetY, 0.0)
        assertEquals(5.0, camera.targetZ, 0.0)
        assertEquals(0.0, camera.panX, 0.0)
        assertEquals(0.0, camera.panY, 0.0)
        assertTrue("Narrower viewport needs extra clearance", camera.distance > squareDistance)
    }

    @Test
    fun zoomLimitsFollowModelSize() {
        val mesh = MeshData(emptyList(), 0f, 0f, 0f, 1f, 1f, 1f)
        val camera = Camera()
        camera.fitModel(mesh, 200, 200)
        val fittedDistance = camera.distance

        camera.zoom(0.9)
        assertTrue("Pinching in should zoom into small models", camera.distance < fittedDistance)
        camera.zoom(0.01)
        assertTrue("Zoom must leave room for the near clipping plane", camera.distance > 1.0)
    }

    @Test
    fun restoresOrbitPanAndRelativeZoomAfterViewportChanges() {
        val mesh = MeshData(emptyList(), 0f, 0f, 0f, 1f, 1f, 1f)
        val source = Camera().apply {
            fitModel(mesh, 100, 200)
            rotate(23.0, -6.0)
            zoom(0.8)
            pan(0.1, -0.2)
        }
        val saved = requireNotNull(source.modelPose())
        val destination = Camera()
        destination.fitModel(mesh, 200, 100, saved)
        val restored = requireNotNull(destination.modelPose())

        assertTrue(restored.fitDistance < saved.fitDistance)
        assertEquals(0.8, restored.view.distance / restored.fitDistance, 0.001)
        assertEquals(saved.view.copy(distance = restored.view.distance), restored.view)
        destination.zoom(0.95)
        assertTrue("Restored camera should retain small-model zoom limits", destination.distance < restored.view.distance)
    }
}
