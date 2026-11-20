package com.u1.slicer

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.View
import android.view.inspector.WindowInspector
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Captures the display, including dialog and IME layers. The caller owns the bitmap. */
internal fun takeFullPresetScreenshot(): Bitmap {
    check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { "Frame capture requires Android 10 or newer" }
    val main = Handler(Looper.getMainLooper())
    val displayed = CountDownLatch(1)
    val status = AtomicReference<String?>("No visible hardware-rendered window for screenshot")
    main.post {
        val roots = WindowInspector.getGlobalWindowViews().filter {
            it.isAttachedToWindow && it.isShown && it.windowVisibility == View.VISIBLE &&
                it.isHardwareAccelerated && it.viewTreeObserver.isAlive
        }
        // A Compose dialog has its own window. Prefer the focused root over the underlying activity.
        val root = roots.firstOrNull { it.hasWindowFocus() } ?: roots.lastOrNull()
        if (root == null) {
            displayed.countDown()
            return@post
        }
        status.set("Timed out waiting for a frame from ${root.javaClass.simpleName}")
        root.viewTreeObserver.registerFrameCommitCallback {
            main.post {
                // Frame commit submits the buffer. Allow display vsyncs for SurfaceFlinger to latch it.
                val choreographer = Choreographer.getInstance()
                choreographer.postFrameCallback {
                    choreographer.postFrameCallback {
                        status.set(null)
                        displayed.countDown()
                    }
                }
            }
        }
        root.invalidate()
    }
    check(displayed.await(5, TimeUnit.SECONDS)) { status.get() ?: "Timed out waiting for screenshot" }
    check(status.get() == null) { requireNotNull(status.get()) }
    return requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
}

internal fun saveFullPresetScreenshot(name: String) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val directory = File(context.filesDir, "preset-workflow-capture")
    check(directory.isDirectory || directory.mkdirs())
    val screenshot = takeFullPresetScreenshot()
    try {
        File(directory, "$name.png").outputStream().use {
            check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    } finally {
        screenshot.recycle()
    }
}
