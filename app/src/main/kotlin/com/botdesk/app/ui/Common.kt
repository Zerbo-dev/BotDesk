package com.botdesk.app.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.botdesk.app.AppContainer
import com.botdesk.app.data.AppConfig
import com.botdesk.app.td.Telegram
import org.drinkless.tdlib.TdApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val LocalApp = staticCompositionLocalOf<AppContainer> { error("AppContainer manquant") }

val Navy = Color(0xFF0A1628)
val Card = Color(0xFF12233F)
val Accent = Color(0xFF4F6BFF)
val Good = Color(0xFF22C55E)
val Warn = Color(0xFFF59E0B)

fun fmtSize(b: Long): String {
    if (b <= 0) return "—"
    val u = arrayOf("o", "Ko", "Mo", "Go")
    var v = b.toDouble()
    var i = 0
    while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
    return String.format(Locale.getDefault(), if (i == 0) "%.0f %s" else "%.1f %s", v, u[i])
}

fun fmtDate(sec: Int): String =
    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(sec * 1000L))

fun ago(sec: Int): String {
    if (sec <= 0) return "—"
    val d = System.currentTimeMillis() / 1000 - sec
    return when {
        d < 60 -> "à l'instant"
        d < 3600 -> "il y a ${d / 60} min"
        d < 86400 -> "il y a ${d / 3600} h"
        else -> "il y a ${d / 86400} j"
    }
}

/** Texte honnête : une activité récente n'est pas la preuve qu'un service est opérationnel. */
fun activityLabel(sec: Int): String = when {
    sec <= 0 -> "Statut inconnu"
    System.currentTimeMillis() / 1000 - sec > 86400 -> "Donnée ancienne · " + ago(sec)
    else -> "Dernier message " + ago(sec)
}

fun containsAny(text: String, csv: String): Boolean {
    val t = text.lowercase()
    return csv.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.any { t.contains(it) }
}

fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
}

@Composable
fun ErrorText(text: String?) {
    if (text != null) Text(text, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 4.dp))
}

/** Bots non masqués (les bots masqués restent accessibles depuis le filtre « Masqués » de l'onglet Bots). */
fun Telegram.visibleBots(cfg: AppConfig): List<TdApi.Chat> = bots().filter { cfg.bots[it.id]?.hidden != true }
