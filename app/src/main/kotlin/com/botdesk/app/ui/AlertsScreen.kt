package com.botdesk.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.botdesk.app.td.Msg
import kotlinx.coroutines.launch

private data class Event(val id: Long, val chatId: Long, val source: String, val date: Int, val text: String, val alert: Boolean)

@Composable
fun AlertsScreen(onOpenChat: (Long) -> Unit) {
    val app = LocalApp.current
    val tg = app.tg
    val scope = rememberCoroutineScope()
    val cfg by app.store.config.collectAsState()
    var events by remember { mutableStateOf<List<Event>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var onlyAlerts by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }

    fun load() {
        if (loading) return
        loading = true
        scope.launch {
            val out = mutableListOf<Event>()
            val sources = tg.visibleBots(cfg).take(20) + cfg.backupChannels.mapNotNull { tg.chat(it) }
            for (c in sources) {
                try {
                    for (m in tg.history(c.id, 0, 30)) {
                        if (m.isOutgoing) continue
                        val t = Msg.text(m)
                        if (t.isNotBlank()) out += Event(m.id, c.id, c.title, m.date, t, containsAny(t, cfg.alertKeywords))
                    }
                } catch (e: Exception) { /* source ignorée */ }
            }
            events = out.sortedByDescending { it.date }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    val shown = events.filter { (!onlyAlerts || it.alert) && (query.isBlank() || it.text.contains(query, true) || it.source.contains(query, true)) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
            Text("Alertes et rapports", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { load() }) { Text(if (loading) "…" else "Actualiser") }
        }
        Text("Une alerte = message contenant un de vos mots-clés (Paramètres).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Switch(onlyAlerts, { onlyAlerts = it }); Spacer(Modifier.width(8.dp)); Text("Alertes seulement")
        }
        OutlinedTextField(query, { query = it }, label = { Text("Rechercher") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f)) {
            if (shown.isEmpty() && !loading) item { Text("Rien à afficher.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp)) }
            items(shown, key = { it.chatId.toString() + "_" + it.id }) { e ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = if (e.alert) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpenChat(e.chatId) },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text((if (e.alert) "⚠ " else "") + e.source + " · " + fmtDate(e.date), style = MaterialTheme.typography.labelMedium, color = if (e.alert) Warn else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(e.text.take(300), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
