package com.botdesk.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Navy,
    onBackground = Color(0xFFE6ECF8),
    surface = Card,
    onSurface = Color(0xFFE6ECF8),
    surfaceVariant = Color(0xFF1A2F52),
    onSurfaceVariant = Color(0xFF9FB0CC),
    secondary = Good,
    error = Color(0xFFFF6B6B),
)

private val Light = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Color(0xFFF4F6FB),
    onBackground = Color(0xFF0A1628),
    surface = Color.White,
    onSurface = Color(0xFF0A1628),
    surfaceVariant = Color(0xFFE3E8F5),
    onSurfaceVariant = Color(0xFF52607A),
    secondary = Good,
    error = Color(0xFFD32F2F),
)

@Composable
fun BotDeskTheme(mode: String = "dark", content: @Composable () -> Unit) {
    val dark = when (mode) {
        "light" -> false
        "system" -> isSystemInDarkTheme()
        else -> true
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
