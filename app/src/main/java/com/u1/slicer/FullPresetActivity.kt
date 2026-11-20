package com.u1.slicer

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.u1.slicer.ui.FullPresetScreen
import com.u1.slicer.ui.FullPresetTheme

/** A separate application variant owns its native scene and imported presets. */
class FullPresetActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.FULL_PRESET_EXPERIMENTAL) {
            finish()
            return
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.WHITE),
        )
        setContent { FullPresetTheme { FullPresetScreen(onBack = { finish() }) } }
    }
}
