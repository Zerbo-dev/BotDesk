@file:OptIn(ExperimentalMaterial3Api::class)

package com.botdesk.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.botdesk.app.td.BackupFile
import com.botdesk.app.td.Msg
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(onOpenChat: (Long) -> Unit, onOpenChannel: (Long) -> Unit, onNavigate: (Tab) -> Unit) {
    val app = LocalApp.current
    val tg = app.tg
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val tick by tg.version.collectAsState()
    val cfg by app.store.config.collectAsState()
    val name by tg.myName.collectAsState()

    val bots = remember(tick) { tg.bots() }
    val favorites = remember(tick, cfg) { bots.filter { cfg.bots[it.id]?.favorite == true } }
    val recent = remember(tick) { bots.take(5) }

    var target by remember { mutableStateOf<Long?>(null) }
    var menu by remember { mutableStateOf(false) }
    var quick by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<String?>(null) }
    val targetId = target ?: favorites.firstOrNull()?.id ?: bots.firstOrNull()?.id
    val targetName = targetId?.let { id -> cfg.bots[id]?.displayName ?: tg.chat(id)?.title } ?: "Aucun bot"

    fun send(text: String) {
        val id = targetId ?: return
        scope.launch {
            try {
                tg.sendText(id, text)
                quick = ""
                onOpenChat(id)
            } catch (e: Exception) { toast(ctx, "Envoi impossible : " + e.message) }
        }
    }

    val lastBackups by produceState<List<Pair<String, BackupFile>>>(emptyList(), cfg.backupChannels, tick > 0) {
        val out = mutableListOf<Pair<String, BackupFile>>()
        for (id in cfg.backupChannels) {
            val title = tg.chat(id)?.title ?: continue
            try { tg.backupFiles(id, 1).take(3).forEach { out += title to it } } catch (e: Exception) { /* ignoré */ }
        }
        value = out.sortedByDescending { it.second.date }.take(4)
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Text("Bonjour" + (name?.let { " $it" } ?: "") + " 👋", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp))
            Text("Que veux-tu faire aujourd'hui ?", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Box {
                        TextButton(onClick = { menu = true }, enabled = bots.isNotEmpty()) { Text("À : $targetName ▾") }
                        DropdownMenu(menu, { menu = false }) {
                            bots.forEach { c ->
                                DropdownMenuItem(text = { Text(cfg.bots[c.id]?.displayName ?: c.title) }, onClick = { target = c.id; menu = false })
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(quick, { quick = it }, placeholder = { Text("Ex. : /status, vérifie mes logs…") }, modifier = Modifier.weight(1f), maxLines = 2)
                        IconButton(onClick = {
                            if (quick.isNotBlank()) { if (containsAny(quick, cfg.sensitiveKeywords)) confirm = quick else send(quick) }
                        }, enabled = quick.isNotBlank() && targetId != null) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Envoyer", tint = Accent)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NavCard("Mes bots", "${bots.size} détecté(s)", Modifier.weight(1f)) { onNavigate(Tab.Bots) }
                NavCard("Discussions", "Voir mes conversations", Modifier.weight(1f)) { onNavigate(Tab.Messages) }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NavCard("Sauvegardes", "${cfg.backupChannels.size} dépôt(s)", Modifier.weight(1f)) { onNavigate(Tab.Backups) }
                NavCard("Paramètres", "Personnaliser l'app", Modifier.weight(1f)) { onNavigate(Tab.Settings) }
            }
        }
        if (favorites.isNotEmpty()) {
            item { SectionTitle("Favoris") }
            items(favorites, key = { "f" + it.id }) { c -> BotRow(cfg.bots[c.id]?.displayName ?: c.title, activityLabel(c.lastMessage?.date ?: 0)) { onOpenChat(c.id) } }
        }
        item { SectionTitle("Activité récente") }
        if (recent.isEmpty()) item { Text("Aucun bot trouvé. Écrivez à un bot depuis Telegram puis actualisez dans l'onglet Bots.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(recent, key = { "r" + it.id }) { c ->
            val last = c.lastMessage
            BotRow(cfg.bots[c.id]?.displayName ?: c.title, activityLabel(last?.date ?: 0) + (last?.let { " · " + Msg.text(it).take(50) } ?: "")) { onOpenChat(c.id) }
        }
        item { SectionTitle("Dernières sauvegardes") }
        if (cfg.backupChannels.isEmpty()) item { Text("Aucun canal de sauvegarde sélectionné (onglet Sauvegardes).", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(lastBackups, key = { "b" + it.second.chatId + "_" + it.second.msgId }) { (title, f) ->
            BotRow(f.doc.fileName, "$title · ${fmtDate(f.date)} · ${fmtSize(f.doc.document.size)}") { onOpenChannel(f.chatId) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    confirm?.let { text ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Confirmer l'action") },
            text = { Text("« $text » ressemble à une action sensible. Envoyer à $targetName ?") },
            confirmButton = { TextButton(onClick = { confirm = null; send(text) }) { Text("Envoyer") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun NavCard(title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
fun BotRow(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}
