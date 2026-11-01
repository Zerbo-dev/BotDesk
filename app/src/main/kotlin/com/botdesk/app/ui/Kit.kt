@file:OptIn(ExperimentalMaterial3Api::class)

package com.botdesk.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Carte standard de l'app : coins arrondis, fine bordure, contenu paddé. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    val inner: @Composable ColumnScope.() -> Unit = { Column(Modifier.padding(padding), content = content) }
    if (onClick != null) Card(onClick = onClick, modifier = modifier, colors = colors, border = border, content = inner)
    else Card(modifier = modifier, colors = colors, border = border, content = inner)
}

/** Pastille ronde avec l'initiale ; couleur stable pour un même nom. */
@Composable
fun Avatar(name: String, size: Dp = 44.dp) {
    val initial = name.trim().firstOrNull()?.uppercase() ?: "?"
    val hue = ((name.hashCode() and 0x7fffffff) % 360).toFloat()
    Box(Modifier.size(size).clip(CircleShape).background(Color.hsv(hue, 0.45f, 0.72f)), contentAlignment = Alignment.Center) {
        Text(initial, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * Point d'activité. Volontairement jamais vert : une activité récente (bleu) ne prouve pas qu'un service fonctionne.
 * Gris = inconnu, orange = donnée ancienne.
 */
@Composable
fun ActivityDot(sec: Int) {
    val c = when {
        sec <= 0 -> Color(0xFF7A8AA8)
        System.currentTimeMillis() / 1000 - sec > 86400 -> Warn
        else -> Accent
    }
    Box(Modifier.size(10.dp).clip(CircleShape).background(c))
}

@Composable
fun Pill(text: String, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.18f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(text, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun FormatBadge(fmt: String) {
    val c = when (fmt.uppercase()) {
        "PDF" -> Color(0xFFE5484D)
        "ZIP", "GZ", "TGZ", "7Z", "TAR" -> Warn
        "SQL", "JSON", "DB", "CSV" -> Accent
        else -> Color(0xFF7A8AA8)
    }
    Pill(fmt.take(6), c)
}

@Composable
fun ScreenHeader(title: String, subtitle: String? = null, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

@Composable
fun SectionHeader(title: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelLarge, color = Accent, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (actionLabel != null) TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
fun EmptyState(emoji: String, title: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

fun fmtTime(sec: Int): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(sec * 1000L))

fun dayKey(sec: Int): Long {
    val ms = sec * 1000L
    return (ms + TimeZone.getDefault().getOffset(ms)) / 86_400_000L
}

fun dayLabel(sec: Int): String {
    val today = dayKey((System.currentTimeMillis() / 1000).toInt())
    val k = dayKey(sec)
    return when {
        k == today -> "Aujourd'hui"
        k == today - 1 -> "Hier"
        else -> SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(sec * 1000L))
    }
}
