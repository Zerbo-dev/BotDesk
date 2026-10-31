@file:OptIn(ExperimentalMaterial3Api::class)

package com.botdesk.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.botdesk.app.data.BotMeta
import com.botdesk.app.td.Msg
import kotlinx.coroutines.launch

@Composable
fun BotsScreen(onOpenChat: (Long) -> Unit) {
    val app = LocalApp.current
    val tg = app.tg
    val scope = rememberCoroutineScope()
    val tick by tg.version.collectAsState()
    val cfg by app.store.config.collectAsState()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    var editing by remember { mutableStateOf<Long?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    val bots = remember(tick, cfg, query, filter) {
        tg.bots().filter { c ->
            val meta = cfg.bots[c.id]
            val name = meta?.displayName ?: c.title
            (query.isBlank() || name.contains(query, true) || (meta?.description ?: "").contains(query, true)) &&
                when (filter) {
                    "all" -> true
                    "fav" -> meta?.favorite == true
                    else -> meta?.categoryId == filter
                }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
            Text("Mes bots", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                scope.launch { refreshing = true; try { tg.loadChats() } catch (e: Exception) { }; refreshing = false }
            }) { if (refreshing) CircularProgressIndicator(Modifier.size(20.dp)) else Icon(Icons.Default.Refresh, "Actualiser") }
        }
        OutlinedTextField(query, { query = it }, label = { Text("Rechercher") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            item { FilterChip(filter == "all", { filter = "all" }, { Text("Tous") }) }
            item { FilterChip(filter == "fav", { filter = "fav" }, { Text("Favoris") }) }
            items(cfg.categories, key = { it.id }) { cat -> FilterChip(filter == cat.id, { filter = cat.id }, { Text(cat.name) }) }
        }
        LazyColumn(Modifier.weight(1f)) {
            if (bots.isEmpty()) item { Text("Aucun bot à afficher.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(bots, key = { it.id }) { c ->
                val meta = cfg.bots[c.id] ?: BotMeta()
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpenChat(c.id) },
                ) {
                    Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(meta.displayName ?: c.title, style = MaterialTheme.typography.titleSmall)
                            meta.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            val cat = cfg.categories.firstOrNull { it.id == meta.categoryId }?.name
                            Text(listOfNotNull(cat, activityLabel(c.lastMessage?.date ?: 0)).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            c.lastMessage?.let { Text(Msg.text(it).take(80), style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        if (c.unreadCount > 0) Badge { Text(c.unreadCount.toString()) }
                        IconButton(onClick = { app.store.update { it.copy(bots = it.bots + (c.id to meta.copy(favorite = !meta.favorite))) } }) {
                            Icon(if (meta.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favori", tint = if (meta.favorite) Accent else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { editing = c.id }) { Icon(Icons.Default.Edit, "Modifier") }
                    }
                }
            }
        }
    }

    editing?.let { id ->
        val chat = tg.chat(id)
        val meta = cfg.bots[id] ?: BotMeta()
        var name by remember(id) { mutableStateOf(meta.displayName ?: "") }
        var desc by remember(id) { mutableStateOf(meta.description ?: "") }
        var cat by remember(id) { mutableStateOf(meta.categoryId) }
        var muted by remember(id) { mutableStateOf(meta.muted) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Modifier le bot") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text(chat?.title ?: "Nom affiché") }, singleLine = true)
                    OutlinedTextField(desc, { desc = it }, label = { Text("Description") })
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Notifications en sourdine", Modifier.weight(1f)); Switch(muted, { muted = it })
                    }
                    Text("Catégorie", modifier = Modifier.padding(top = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(cat == null, { cat = null }); Text("Aucune") }
                    cfg.categories.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(cat == c.id, { cat = c.id }); Text(c.name) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    app.store.update {
                        it.copy(bots = it.bots + (id to meta.copy(displayName = name.ifBlank { null }, description = desc.ifBlank { null }, categoryId = cat, muted = muted)))
                    }
                    editing = null
                }) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Annuler") } },
        )
    }
}
