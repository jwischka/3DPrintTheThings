package com.u1.slicer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.u1.slicer.ui.FullPresetScreen

/** A separate application variant owns its native scene and imported presets. */
class FullPresetActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.FULL_PRESET_EXPERIMENTAL) {
            finish()
            return
        }
        setContent { U1SlicerTheme { FullPresetScreen(onBack = { finish() }) } }
    }
}
