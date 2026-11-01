@file:OptIn(ExperimentalMaterial3Api::class)

package com.botdesk.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
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
    var hits by remember { mutableStateOf<List<Hit>?>(null) }
    var searching by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val id = editing
        if (uri != null && id != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { AvatarStore.save(ctx, id, uri) }
            if (ok) app.store.update { c -> val m = c.bots[id] ?: BotMeta(); c.copy(bots = c.bots + (id to m.copy(avatarVersion = m.avatarVersion + 1))) }
            else toast(ctx, "Image illisible")
        }
    }

    fun searchHistory() {
        val q = query.trim()
        if (q.isEmpty() || searching) return
        searching = true
        hits = emptyList()
        scope.launch {
            val out = mutableListOf<Hit>()
            val sources = tg.visibleBots(cfg).take(30) + cfg.backupChannels.mapNotNull { tg.chat(it) }
            for (c in sources) {
                try {
                    tg.searchInChat(c.id, q, 15).forEach { m -> out += Hit(m.id, c.id, c.title, m.date, Msg.text(m)) }
                } catch (e: Exception) { /* source ignorée */ }
            }
            hits = out.sortedByDescending { it.date }
            searching = false
        }
    }
    val hiddenCount = remember(tick, cfg) { tg.bots().count { cfg.bots[it.id]?.hidden == true } }

    val bots = remember(tick, cfg, query, filter) {
        tg.bots().filter { c ->
            val meta = cfg.bots[c.id]
            val name = meta?.displayName ?: c.title
            ((filter == "hidden") == (meta?.hidden == true)) &&
            (query.isBlank() || name.contains(query, true) || (meta?.description ?: "").contains(query, true)) &&
                when (filter) {
                    "all" -> true
                    "fav" -> meta?.favorite == true
                    "hidden" -> true
                    else -> meta?.categoryId == filter
                }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        ScreenHeader("Mes bots", "${bots.size} affiché(s)") {
            IconButton(onClick = {
                scope.launch { refreshing = true; try { tg.loadChats() } catch (e: Exception) { }; refreshing = false }
            }) { if (refreshing) CircularProgressIndicator(Modifier.size(20.dp)) else Icon(Icons.Default.Refresh, "Actualiser") }
        }
        OutlinedTextField(
            query, { query = it; hits = null }, label = { Text("Rechercher un bot") }, singleLine = true, shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { searchHistory() }),
            modifier = Modifier.fillMaxWidth(),
        )
        if (query.isNotBlank()) {
            TextButton(onClick = { searchHistory() }) { Text(if (searching) "Recherche…" else "Chercher « ${query.trim()} » dans tout l'historique") }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            item { FilterChip(filter == "all", { filter = "all" }, { Text("Tous") }) }
            item { FilterChip(filter == "fav", { filter = "fav" }, { Text("Favoris") }) }
            if (hiddenCount > 0 || filter == "hidden") item { FilterChip(filter == "hidden", { filter = "hidden" }, { Text("Masqués ($hiddenCount)") }) }
            items(cfg.categories, key = { it.id }) { cat -> FilterChip(filter == cat.id, { filter = cat.id }, { Text(cat.name) }) }
        }
        LazyColumn(Modifier.weight(1f)) {
            val h = hits
            if (h != null) {
                item {
                    Text(
                        if (searching) "Recherche dans l'historique…" else "${h.size} résultat(s) dans l'historique (messages des bots et des dépôts)",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
                items(h, key = { it.chatId.toString() + "_" + it.id }) { r ->
                    AppCard(Modifier.fillMaxWidth().padding(vertical = 4.dp), onClick = { onOpenChat(r.chatId) }, padding = 12.dp) {
                        Column {
                            Text(r.source + " · " + fmtDate(r.date), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(r.text.take(250))
                        }
                    }
                }
            }
            if (h == null && bots.isEmpty()) item { EmptyState("🤖", "Aucun bot à afficher", "Écrivez à un bot depuis Telegram, puis actualisez avec le bouton en haut.") }
            items(if (h == null) bots else emptyList(), key = { it.id }) { c ->
                val meta = cfg.bots[c.id] ?: BotMeta()
                val shownName = meta.displayName ?: c.title
                AppCard(Modifier.fillMaxWidth().padding(vertical = 4.dp), onClick = { onOpenChat(c.id) }, padding = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BotAvatar(c.id, shownName)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(shownName, style = MaterialTheme.typography.titleSmall, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                                Spacer(Modifier.width(6.dp))
                                ActivityDot(c.lastMessage?.date ?: 0)
                            }
                            meta.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                            c.lastMessage?.let { Text(Msg.text(it).take(80), style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            val cat = cfg.categories.firstOrNull { it.id == meta.categoryId }?.name
                            Text(listOfNotNull(cat, activityLabel(c.lastMessage?.date ?: 0)).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        var hidden by remember(id) { mutableStateOf(meta.hidden) }
        var emoji by remember(id) { mutableStateOf(meta.avatarEmoji ?: "") }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Modifier le bot") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BotAvatar(id, name.ifBlank { chat?.title ?: "?" }, 56.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            TextButton(onClick = { app.lock.skipNextLock = true; picker.launch("image/*") }) { Text("Choisir une image") }
                            if (meta.avatarVersion > 0) {
                                TextButton(onClick = {
                                    AvatarStore.delete(ctx, id)
                                    app.store.update { c -> val m = c.bots[id] ?: BotMeta(); c.copy(bots = c.bots + (id to m.copy(avatarVersion = 0))) }
                                }) { Text("Retirer l'image") }
                            }
                        }
                    }
                    OutlinedTextField(emoji, { emoji = it.take(4) }, label = { Text("Ou un emoji (ex. 🎵)") }, singleLine = true)
                    OutlinedTextField(name, { name = it }, label = { Text(chat?.title ?: "Nom affiché") }, singleLine = true)
                    OutlinedTextField(desc, { desc = it }, label = { Text("Description") })
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Notifications en sourdine", Modifier.weight(1f)); Switch(muted, { muted = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Masquer ce bot", Modifier.weight(1f)); Switch(hidden, { hidden = it })
                    }
                    if (hidden) Text("Un bot masqué disparaît des listes, de l'accueil, des alertes et des notifications. Retrouvez-le via le filtre « Masqués ».", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        it.copy(bots = it.bots + (id to (it.bots[id] ?: BotMeta()).copy(displayName = name.ifBlank { null }, description = desc.ifBlank { null }, categoryId = cat, muted = muted, hidden = hidden, avatarEmoji = emoji.trim().ifBlank { null })))
                    }
                    editing = null
                }) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Annuler") } },
        )
    }
}

private data class Hit(val id: Long, val chatId: Long, val source: String, val date: Int, val text: String)
