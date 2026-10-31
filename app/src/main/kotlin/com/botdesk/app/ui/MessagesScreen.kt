package com.botdesk.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.botdesk.app.td.Msg
import kotlinx.coroutines.launch

private data class Hit(val id: Long, val chatId: Long, val source: String, val date: Int, val text: String)

/** Conversations (ordre chronologique) + recherche dans l'historique des bots et des dépôts. */
@Composable
fun MessagesScreen(onOpenChat: (Long) -> Unit) {
    val app = LocalApp.current
    val tg = app.tg
    val scope = rememberCoroutineScope()
    val tick by tg.version.collectAsState()
    val cfg by app.store.config.collectAsState()
    val chats = remember(tick) { tg.bots() }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Hit>?>(null) }
    var searching by remember { mutableStateOf(false) }

    fun search() {
        val q = query.trim()
        if (q.isEmpty()) { hits = null; return }
        if (searching) return
        searching = true
        scope.launch {
            val out = mutableListOf<Hit>()
            val sources = tg.bots().take(30) + cfg.backupChannels.mapNotNull { tg.chat(it) }
            for (c in sources) {
                try {
                    tg.searchInChat(c.id, q, 15).forEach { m -> out += Hit(m.id, c.id, c.title, m.date, Msg.text(m)) }
                } catch (e: Exception) { /* source ignorée */ }
            }
            hits = out.sortedByDescending { it.date }
            searching = false
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("Messages", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp))
        OutlinedTextField(
            query, { query = it; if (it.isBlank()) hits = null },
            label = { Text("Rechercher dans l'historique") }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }),
            modifier = Modifier.fillMaxWidth(),
        )
        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        val h = hits
        LazyColumn(Modifier.weight(1f)) {
            if (h != null) {
                if (h.isEmpty() && !searching) item { Text("Aucun résultat.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp)) }
                items(h, key = { it.chatId.toString() + "_" + it.id }) { r ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpenChat(r.chatId) }) {
                        Column(Modifier.padding(12.dp)) {
                            Text(r.source + " · " + fmtDate(r.date), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(r.text.take(250))
                        }
                    }
                }
            } else {
                if (chats.isEmpty()) item { Text("Aucune conversation avec un bot.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp)) }
                items(chats, key = { it.id }) { c ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpenChat(c.id) }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(cfg.bots[c.id]?.displayName ?: c.title, style = MaterialTheme.typography.titleSmall)
                                Text(c.lastMessage?.let { Msg.text(it).take(80) } ?: "Aucun message", style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(c.lastMessage?.let { fmtDate(it.date) } ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (c.unreadCount > 0) Badge { Text(c.unreadCount.toString()) }
                        }
                    }
                }
            }
        }
    }
}
