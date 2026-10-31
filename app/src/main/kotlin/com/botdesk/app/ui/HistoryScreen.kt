@file:OptIn(ExperimentalMaterial3Api::class)

package com.botdesk.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Historique local des fichiers vus ou téléchargés (métadonnées uniquement), consultable hors connexion. */
@Composable
fun HistoryScreen(onOpen: (Long) -> Unit, onBack: () -> Unit) {
    val app = LocalApp.current
    val all by app.index.items.collectAsState()
    var query by remember { mutableStateOf("") }
    var onlyDownloaded by remember { mutableStateOf(false) }
    val shown = all.filter {
        (!onlyDownloaded || it.downloaded) &&
            (query.isBlank() || it.name.contains(query, true) || it.caption.contains(query, true) ||
                it.sender.contains(query, true) || it.chatTitle.contains(query, true))
    }.sortedByDescending { it.date }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Historique des fichiers") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
        )
        Column(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("Rechercher") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Switch(onlyDownloaded, { onlyDownloaded = it }); Spacer(Modifier.width(8.dp)); Text("Téléchargés seulement")
            }
            Text("${all.size} fichier(s) indexé(s). Durée de conservation réglable dans Réglages.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            if (shown.isEmpty()) item { Text("Aucun fichier.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp)) }
            items(shown, key = { it.chatId.toString() + "_" + it.msgId }) { f ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpen(f.chatId) },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text((if (f.downloaded) "⬇ " else "") + f.name, style = MaterialTheme.typography.titleSmall)
                        Text("${f.chatTitle} · ${fmtDate(f.date)} · ${fmtSize(f.size)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (f.sender.isNotBlank()) Text("Expéditeur : " + f.sender, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
