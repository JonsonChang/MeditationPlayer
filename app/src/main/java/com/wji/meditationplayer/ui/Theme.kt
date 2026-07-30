package com.wji.meditationplayer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFE8C77B),
    onPrimary = Color(0xFF352A12),
    secondary = Color(0xFF8FB8C9),
    background = Color(0xFF12181F),
    surface = Color(0xFF1B2A41),
    onSurface = Color(0xFFE7EAF0),
)

/** 冥想情境常在昏暗環境使用，固定深色。 */
@Composable
fun MeditationTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, content = content)
}
