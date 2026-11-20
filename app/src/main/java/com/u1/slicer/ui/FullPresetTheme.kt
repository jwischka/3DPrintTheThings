package com.u1.slicer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val fullPresetColors = lightColorScheme(
    primary = Color(0xFF08734F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7F3E5),
    onPrimaryContainer = Color(0xFF034630),
    secondary = Color(0xFF496355),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDEDE4),
    onSecondaryContainer = Color(0xFF213E30),
    background = Color(0xFFF3F6F4),
    onBackground = Color(0xFF17241E),
    surface = Color.White,
    onSurface = Color(0xFF17241E),
    surfaceVariant = Color(0xFFE9EFEB),
    onSurfaceVariant = Color(0xFF4C5E55),
    surfaceTint = Color(0xFF08734F),
    outline = Color(0xFF687C70),
    outlineVariant = Color(0xFFCED9D2),
)

@Composable
internal fun FullPresetTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = fullPresetColors, content = content)
}
