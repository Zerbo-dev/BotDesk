package com.botdesk.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.botdesk.app.td.BackupFile

@Composable
fun HomeScreen(onOpenChat: (Long) -> Unit, onOpenChannel: (Long) -> Unit) {
    val app = LocalApp.current
    val tg = app.tg
    val tick by tg.version.collectAsState()
    val cfg by app.store.config.collectAsState()

    val bots = remember(tick) { tg.bots() }
    val favorites = remember(tick, cfg) { bots.filter { cfg.bots[it.id]?.favorite == true } }
    val recent = remember(tick) { bots.take(6) }

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
            Text("BotDesk", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp))
            Text("${bots.size} bot(s) détecté(s) · ${cfg.backupChannels.size} dépôt(s) de sauvegarde",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (favorites.isNotEmpty()) {
            item { SectionTitle("Favoris") }
            items(favorites, key = { "f" + it.id }) { c -> BotRow(c.title, activityLabel(c.lastMessage?.date ?: 0)) { onOpenChat(c.id) } }
        }
        item { SectionTitle("Activité récente") }
        if (recent.isEmpty()) item { Text("Aucun bot trouvé. Écrivez à un bot depuis Telegram puis actualisez dans l'onglet Bots.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(recent, key = { "r" + it.id }) { c ->
            val last = c.lastMessage
            BotRow(cfg.bots[c.id]?.displayName ?: c.title, activityLabel(last?.date ?: 0) + (last?.let { " · " + com.botdesk.app.td.Msg.text(it).take(50) } ?: "")) { onOpenChat(c.id) }
        }
        item { SectionTitle("Dernières sauvegardes") }
        if (cfg.backupChannels.isEmpty()) item { Text("Aucun canal de sauvegarde sélectionné (onglet Sauvegardes).", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(lastBackups, key = { "b" + it.second.chatId + "_" + it.second.msgId }) { (title, f) ->
            BotRow(f.doc.fileName, "$title · ${fmtDate(f.date)} · ${fmtSize(f.doc.document.size)}") { onOpenChannel(f.chatId) }
        }
        item { Spacer(Modifier.height(24.dp)) }
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
