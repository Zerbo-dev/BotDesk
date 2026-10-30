@file:OptIn(ExperimentalMaterial3Api::class)

package com.botdesk.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.botdesk.app.td.BackupFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BackupsScreen(onOpenChannel: (Long) -> Unit) {
    val app = LocalApp.current
    val tg = app.tg
    val tick by tg.version.collectAsState()
    val cfg by app.store.config.collectAsState()
    val channels = remember(tick) { tg.channels() }
    val selected = channels.filter { it.id in cfg.backupChannels }
    val others = channels.filter { it.id !in cfg.backupChannels }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Text("Sauvegardes", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp))
            Text("Activez les canaux ou groupes privés où vos serveurs publient leurs sauvegardes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { SectionTitle("Dépôts de sauvegarde") }
        if (selected.isEmpty()) item { Text("Aucun dépôt sélectionné.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(selected, key = { "s" + it.id }) { c -> ChannelRow(c.title, tg.channelKind(c), true, { onOpenChannel(c.id) }) { on -> toggle(app, c.id, on) } }
        item { SectionTitle("Autres canaux et groupes") }
        items(others, key = { "o" + it.id }) { c -> ChannelRow(c.title, tg.channelKind(c), false, {}) { on -> toggle(app, c.id, on) } }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private fun toggle(app: com.botdesk.app.AppContainer, id: Long, on: Boolean) {
    app.store.update { it.copy(backupChannels = if (on) it.backupChannels + id else it.backupChannels - id) }
}

@Composable
private fun ChannelRow(title: String, kind: String, selected: Boolean, onOpen: () -> Unit, onToggle: (Boolean) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        onClick = { if (selected) onOpen() },
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(kind + if (selected) " · toucher pour parcourir" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(selected, onToggle)
        }
    }
}

@Composable
fun BackupFilesScreen(chatId: Long, onBack: () -> Unit) {
    val app = LocalApp.current
    val tg = app.tg
    val scope = rememberCoroutineScope()
    val save = rememberFileSaver()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var files by remember(chatId) { mutableStateOf<List<BackupFile>?>(null) }
    var error by remember(chatId) { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(chatId) {
        try { files = tg.backupFiles(chatId) } catch (e: Exception) { error = e.message; files = emptyList() }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(tg.chat(chatId)?.title ?: "Sauvegardes") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
        )
        Column(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("Rechercher (nom, légende)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ErrorText(error)
            Text("Jusqu'à 500 messages récents analysés. Les fichiers sont téléchargés tels quels, sans modification.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
        }
        val list = files
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            val shown = list.filter { query.isBlank() || it.doc.fileName.contains(query, true) || it.caption.contains(query, true) }
            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                if (shown.isEmpty()) item { Text("Aucun fichier trouvé.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(shown, key = { it.msgId }) { f ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(f.doc.fileName, style = MaterialTheme.typography.titleSmall)
                            Text("${fmtDate(f.date)} · ${fmtSize(f.doc.document.size)} · ${FormatDetector.guess(f.doc.fileName, f.doc.mimeType)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (f.caption.isNotBlank()) Text(f.caption, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                            Row {
                                TextButton(onClick = { save(f.doc) }) { Text("Télécharger") }
                                TextButton(onClick = {
                                    val h = PreviewRegistry.find(f.doc.fileName, f.doc.mimeType, f.doc.document.size)
                                    if (h == null) {
                                        toast(ctx, "Aperçu non disponible pour ce format (${FormatDetector.guess(f.doc.fileName, f.doc.mimeType)})")
                                    } else scope.launch {
                                        try {
                                            val d = tg.download(f.doc.document.id)
                                            val text = withContext(Dispatchers.IO) { h.preview(d.local.path) }
                                            preview = f.doc.fileName to text
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
