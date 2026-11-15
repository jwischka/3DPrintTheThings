package com.u1.slicer.viewer

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.gcode.GcodeLayer
import com.u1.slicer.gcode.GcodeMove
import com.u1.slicer.gcode.MoveType
import com.u1.slicer.gcode.ParsedGcode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real ES 3.0 renderer after Android discards and recreates its EGL context. */
@RunWith(AndroidJUnit4::class)
class GcodeRendererContextInstrumentedTest {
    @Test
    fun gcodeTextureStorageIsRestoredAfterContextRecreation() {
        val egl = OffscreenEgl()
        try {
            val renderer = GcodeRenderer(InstrumentationRegistry.getInstrumentation().targetContext)
            val gcode = ParsedGcode(listOf(
                GcodeLayer(0, 0.2f, listOf(GcodeMove(MoveType.EXTRUDE, 40f, 40f, 80f, 40f))),
                GcodeLayer(1, 0.4f, listOf(GcodeMove(MoveType.EXTRUDE, 40f, 50f, 80f, 50f)))
            ))
            renderer.pendingGcode = gcode

            egl.makeCurrent()
            renderer.onSurfaceCreated(null, null)
            renderer.onSurfaceChanged(null, 128, 128)
            renderer.onDrawFrame(null)
            assertEquals("Initial G-code texture has no storage", GLES30.GL_FRAMEBUFFER_COMPLETE,
                segmentIndexFramebufferStatus(renderer))
            assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
            renderer.maxLayer = 0
            renderer.camera.distance = 180.0
            renderer.camera.panX = 7.5

            egl.recreateContext()
            renderer.onSurfaceCreated(null, null)
            renderer.onSurfaceChanged(null, 128, 128)
            renderer.onDrawFrame(null)
            assertEquals("G-code texture storage must be rebuilt after context loss",
                GLES30.GL_FRAMEBUFFER_COMPLETE, segmentIndexFramebufferStatus(renderer))
            assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
            assertEquals("Selected layer must survive context loss", 0, renderer.maxLayer)
            assertEquals("Camera distance must survive context loss", 180.0,
                renderer.camera.distance, 0.001)
            assertEquals("Camera pan must survive context loss", 7.5,
                renderer.camera.panX, 0.001)
        } finally {
            egl.close()
        }
    }

    private fun segmentIndexFramebufferStatus(renderer: GcodeRenderer): Int {
        val field = GcodeRenderer::class.java.getDeclaredField("segmentIndexTexId")
        field.isAccessible = true
        val texture = field.getInt(renderer)
        assertTrue("No segment-index texture was generated", texture != 0)
        val framebuffer = IntArray(1)
        GLES30.glGenFramebuffers(1, framebuffer, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer[0])
        return try {
            // R32UI is a required color-renderable format in OpenGL ES 3.0.
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, texture, 0)
            GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glDeleteFramebuffers(1, framebuffer, 0)
        }
    }

    private class OffscreenEgl : AutoCloseable {
        private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val config: EGLConfig
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

        init {
            assertTrue("EGL display unavailable", display != EGL14.EGL_NO_DISPLAY)
            assertTrue("EGL initialization failed", EGL14.eglInitialize(display, IntArray(2), 0,
                IntArray(2), 0))
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val attributes = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT_KHR
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            )
            assertTrue("No ES3 pbuffer config", EGL14.eglChooseConfig(display, attributes, 0,
                configs, 0, 1, count, 0) && count[0] > 0)
            config = requireNotNull(configs[0])
        }

        fun makeCurrent() {
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            assertTrue("ES3 context creation failed", context != EGL14.EGL_NO_CONTEXT)
            surface = EGL14.eglCreatePbufferSurface(display, config,
                intArrayOf(EGL14.EGL_WIDTH, 128, EGL14.EGL_HEIGHT, 128, EGL14.EGL_NONE), 0)
            assertTrue("Pbuffer creation failed", surface != EGL14.EGL_NO_SURFACE)
            assertTrue("Could not bind ES3 context", EGL14.eglMakeCurrent(display, surface,
                surface, context))
        }

        fun recreateContext() {
            releaseContext()
            makeCurrent()
        }

        private fun releaseContext() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            surface = EGL14.EGL_NO_SURFACE
            context = EGL14.EGL_NO_CONTEXT
        }

        override fun close() {
            releaseContext()
            EGL14.eglTerminate(display)
        }
    }
}
