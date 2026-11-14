package com.u1.slicer

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real Service and NotificationManager delivery without native or printer work. */
@RunWith(AndroidJUnit4::class)
class LongOpServiceInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun staleStopKeepsCurrentPreparationInForeground() {
        compose.setContent { Text("Foreground service test") }
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val notifications = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            assertEquals("Notification permission is required for this service assertion",
                PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        }
        LongOpService.resetForTest()
        context.stopService(Intent(context, LongOpService::class.java))
        try {
            waitUntil { activeNotification(notifications) == null }

            LongOpService.startPure("Old preparation")
            val oldStop = LongOpService.stopPure()
            assertEquals(LongOpService.ACTION_STOP, oldStop.action)
            LongOpService.startPure("New preparation")
            LongOpService.updatePure(62, null)
            assertNull(activeNotification(notifications))

            // This is the only actual service command: observing the notification
            // proves its delivery, rather than racing against an earlier notification.
            context.startForegroundService(Intent(context, LongOpService::class.java).apply {
                action = oldStop.action
            })
            // Android 12+ may defer default foreground-service notification visibility
            // for about 10 seconds: developer.android.com/develop/background-work/background-tasks.
            waitUntil(20_000, notifications) {
                notificationText(notifications)?.contains("New preparation... (62%)") == true
            }
            val afterOldStop = activeNotification(notifications)
            assertNotNull("Old STOP removed the new foreground notification", afterOldStop)
            assertTrue(notificationText(notifications).orEmpty().contains("New preparation... (62%)"))
            assertEquals(62, afterOldStop!!.notification.extras.getInt(Notification.EXTRA_PROGRESS))

            LongOpService.stop(context)
            waitUntil { activeNotification(notifications) == null }
            assertNull(activeNotification(notifications))
        } finally {
            LongOpService.resetForTest()
            context.stopService(Intent(context, LongOpService::class.java))
            notifications.cancel(NOTIFICATION_ID)
        }
    }

    private fun activeNotification(manager: NotificationManager) =
        manager.activeNotifications.firstOrNull { it.id == NOTIFICATION_ID }

    private fun notificationText(manager: NotificationManager): String? =
        activeNotification(manager)?.notification?.extras
            ?.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private fun waitUntil(timeoutMillis: Long = 5_000, manager: NotificationManager? = null,
                          condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (!condition()) {
            if (SystemClock.uptimeMillis() >= deadline) {
                val active = manager?.activeNotifications?.joinToString { item ->
                    "id=${item.id} text=${item.notification.extras.getCharSequence(Notification.EXTRA_TEXT)} " +
                        "progress=${item.notification.extras.getInt(Notification.EXTRA_PROGRESS)}"
                }
                throw AssertionError("Timed out waiting for service notification; " +
                    "stack=${LongOpService.currentStageOrNull()}, active=$active")
            }
            SystemClock.sleep(25)
        }
    }

    private companion object { const val NOTIFICATION_ID = 1 }
}
