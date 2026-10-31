@file:OptIn(ExperimentalMaterial3Api::class)

package com.botdesk.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.botdesk.app.data.toIndexed
import com.botdesk.app.td.BackupFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BackupsScreen(onOpenChannel: (Long) -> Unit, onOpenHistory: () -> Unit) {
    val app = LocalApp.current
    val tg = app.tg
    val tick by tg.version.collectAsState()
    val cfg by app.store.config.collectAsState()
    var picking by remember { mutableStateOf(false) }
    val selected = remember(tick, cfg) { cfg.backupChannels.mapNotNull { tg.chat(it) }.sortedByDescending { it.lastMessage?.date ?: 0 } }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Text("Sauvegardes", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                Button(onClick = { picking = true }) { Text(if (selected.isEmpty()) "Choisir les dépôts" else "Gérer les dépôts") }
                OutlinedButton(onClick = onOpenHistory) { Text("Historique") }
            }
        }
        if (selected.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Aucun dépôt de sauvegarde", style = MaterialTheme.typography.titleSmall)
                        Text("Choisissez les canaux ou groupes Telegram où vos serveurs publient leurs sauvegardes. Seuls ceux-là apparaîtront ici.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        items(selected, key = { it.id }) { c ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                onClick = { onOpenChannel(c.id) },
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.title, style = MaterialTheme.typography.titleSmall)
                        Text(tg.channelKind(c) + " · " + activityLabel(c.lastMessage?.date ?: 0), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (picking) DepotPicker(onDismiss = { picking = false })
}

/** Sélecteur de dépôts : la liste complète n'apparaît qu'ici, jamais dans l'écran principal. */
@Composable
private fun DepotPicker(onDismiss: () -> Unit) {
    val app = LocalApp.current
    val tg = app.tg
    val tick by tg.version.collectAsState()
    val cfg by app.store.config.collectAsState()
    var query by remember { mutableStateOf("") }
    val all = remember(tick, cfg, query) {
        tg.channels()
            .filter { query.isBlank() || it.title.contains(query, true) }
            .sortedByDescending { it.id in cfg.backupChannels }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dépôts de sauvegarde") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, label = { Text("Rechercher un canal ou groupe") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 380.dp).padding(top = 8.dp)) {
                    if (all.isEmpty()) item { Text("Aucun canal ou groupe trouvé.") }
                    items(all, key = { it.id }) { c ->
                        val on = c.id in cfg.backupChannels
                        Row(
                            Modifier.fillMaxWidth().clickable { toggleDepot(app, c.id, !on) }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(on, { toggleDepot(app, c.id, it) })
                            Column {
                                Text(c.title, maxLines = 1)
                                Text(tg.channelKind(c), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Terminé") } },
    )
}

private fun toggleDepot(app: com.botdesk.app.AppContainer, id: Long, on: Boolean) {
    app.store.update { it.copy(backupChannels = if (on) it.backupChannels + id else it.backupChannels - id) }
}

@Composable
fun BackupFilesScreen(chatId: Long, onBack: () -> Unit) {
    val app = LocalApp.current
    val tg = app.tg
    val scope = rememberCoroutineScope()
    val save = rememberFileSaver()
    val ctx = LocalContext.current
    var files by remember(chatId) { mutableStateOf<List<BackupFile>?>(null) }
    var error by remember(chatId) { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var format by remember { mutableStateOf("Tous") }
    var preview by remember { mutableStateOf<Pair<String, String>?>(null) }
    val title = tg.chat(chatId)?.title ?: "Sauvegardes"

    LaunchedEffect(chatId) {
        try {
            val list = tg.backupFiles(chatId)
            files = list
            app.index.record(list.map { it.toIndexed(title) })
        } catch (e: Exception) { error = e.message; files = emptyList() }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
        )
        Column(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("Rechercher (nom, légende, expéditeur)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            val formats = listOf("Tous") + (files ?: emptyList()).map { FormatDetector.guess(it.doc.fileName, it.doc.mimeType) }.distinct().sorted()
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                items(formats, key = { it }) { f -> FilterChip(format == f, { format = f }, { Text(f) }) }
            }
            ErrorText(error)
            Text("Jusqu'à 500 messages récents analysés. Les fichiers sont téléchargés tels quels, sans modification.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        }
        val list = files
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            val shown = list.filter {
                (format == "Tous" || FormatDetector.guess(it.doc.fileName, it.doc.mimeType) == format) &&
                    (query.isBlank() || it.doc.fileName.contains(query, true) || it.caption.contains(query, true) || it.sender.contains(query, true))
            }
            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                if (shown.isEmpty()) item { Text("Aucun fichier trouvé.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(shown, key = { it.msgId }) { f ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(f.doc.fileName, style = MaterialTheme.typography.titleSmall)
                            Text("${fmtDate(f.date)} · ${fmtSize(f.doc.document.size)} · ${FormatDetector.guess(f.doc.fileName, f.doc.mimeType)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (f.sender.isNotBlank()) Text("Expéditeur : " + f.sender, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (f.caption.isNotBlank()) Text(f.caption, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                            Row {
                                TextButton(onClick = { save(f) }) { Text("Télécharger") }
                                TextButton(onClick = {
                                    val h = PreviewRegistry.find(f.doc.fileName, f.doc.mimeType, f.doc.document.size)
                                    if (h == null) {
                                        toast(ctx, "Aperçu non disponible pour ce format (${FormatDetector.guess(f.doc.fileName, f.doc.mimeType)})")
                                    } else scope.launch {
                                        try {
                                            val d = tg.download(f.doc.document.id)
                                            val text = withContext(Dispatchers.IO) { h.preview(d.local.path) }
                                            app.index.record(listOf(f.toIndexed(title, downloaded = true)))
                                            val sn = FormatDetector.sniff(d.local.path)
                                            preview = (f.doc.fileName + (sn?.let { " · détecté : $it" } ?: "")) to text
                                        } catch (e: Exception) { toast(ctx, "Aperçu impossible : " + e.message) }
                                    }
                                }) { Text("Aperçu") }
                            }
                        }
                    }
                }
            }
        }
    }

    preview?.let { (name, text) ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(name) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(text, style = MaterialTheme.typography.bodySmall) } },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("Fermer") } },
        )
    }
}
