package com.botdesk.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Scheme = darkColorScheme(
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

@Composable
fun BotDeskTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
