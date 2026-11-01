package com.botdesk.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val Dark = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Navy,
    onBackground = Color(0xFFE6ECF8),
    surface = Card,
    onSurface = Color(0xFFE6ECF8),
    surfaceVariant = Color(0xFF1A2F52),
    onSurfaceVariant = Color(0xFF9FB0CC),
    outline = Color(0xFF243B63),
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
    outline = Color(0xFFD8DFEE),
    secondary = Good,
    error = Color(0xFFD32F2F),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val Base = Typography()
private val AppTypography = Base.copy(
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.Bold),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
)

@Composable
fun BotDeskTheme(mode: String = "dark", content: @Composable () -> Unit) {
    val dark = when (mode) {
        "light" -> false
        "system" -> isSystemInDarkTheme()
        else -> true
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, shapes = AppShapes, typography = AppTypography, content = content)
}
