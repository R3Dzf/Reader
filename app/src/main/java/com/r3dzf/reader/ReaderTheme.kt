package com.r3dzf.reader

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF145DA0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEBFA),
    onPrimaryContainer = Color(0xFF0C3154),
    secondary = Color(0xFF376C79),
    background = Color(0xFFF6F8FB),
    surface = Color.White,
    surfaceVariant = Color(0xFFE9EEF4)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8DCBFF),
    onPrimary = Color(0xFF003354),
    primaryContainer = Color(0xFF17476A),
    onPrimaryContainer = Color(0xFFD2E9FF),
    secondary = Color(0xFFA2D5E1),
    background = Color(0xFF10151B),
    surface = Color(0xFF171E26),
    surfaceVariant = Color(0xFF222B35)
)

@Composable
fun ReaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
