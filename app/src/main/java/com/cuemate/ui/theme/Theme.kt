package com.cuemate.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CueColors = darkColorScheme(
    primary = Color(0xFFE2E8F0),
    secondary = Color(0xFF94A3B8),
    background = Color(0xFF0F172A),
    surface = Color(0xFF111827),
    onPrimary = Color(0xFF0F172A),
    onSecondary = Color(0xFF0F172A),
    onBackground = Color(0xFFE2E8F0),
    onSurface = Color(0xFFE2E8F0),
)

@Composable
fun CueMateTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CueColors,
        content = content,
    )
}
