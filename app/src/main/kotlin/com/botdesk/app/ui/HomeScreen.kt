@file:OptIn(ExperimentalMaterial3Api::class)

package com.botdesk.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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

    val bots = remember(tick, cfg) { tg.visibleBots(cfg) }
    val favorites = remember(tick, cfg) { bots.filter { cfg.bots[it.id]?.favorite == true } }
    val recent = remember(tick) { bots.take(5) }

    var target by remember { mutableStateOf<Long?>(null) }
    var bannerDismissed by remember { mutableStateOf(false) }
    val notifAllowed = remember(tick) { NotificationManagerCompat.from(ctx).areNotificationsEnabled() }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val needsNotifSetup = !bannerDismissed && cfg.notifyBots != "off" && (!notifAllowed || !cfg.backgroundService)
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
            Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(name ?: "B", 48.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Bonjour" + (name?.let { " $it" } ?: "") + " 👋", style = MaterialTheme.typography.headlineSmall)
                    Text("Que veux-tu faire aujourd'hui ?", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(16.dp))

            AppCard(Modifier.fillMaxWidth()) {
                Box {
                    TextButton(onClick = { menu = true }, enabled = bots.isNotEmpty()) { Text("À : $targetName ▾") }
                    DropdownMenu(menu, { menu = false }) {
                        bots.forEach { c ->
                            DropdownMenuItem(text = { Text(cfg.bots[c.id]?.displayName ?: c.title) }, onClick = { target = c.id; menu = false })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    OutlinedTextField(
                        quick, { quick = it }, placeholder = { Text("Ex. : /status, vérifie mes logs…") },
                        shape = RoundedCornerShape(24.dp), modifier = Modifier.weight(1f), maxLines = 2,
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(if (quick.isNotBlank() && targetId != null) Accent else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable(enabled = quick.isNotBlank() && targetId != null) {
                                if (containsAny(quick, cfg.sensitiveKeywords)) confirm = quick else send(quick)
                            },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.AutoMirrored.Filled.Send, "Envoyer", tint = Color.White) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile(Icons.Default.Face, "Mes bots", "${bots.size} détecté(s)", Modifier.weight(1f)) { onNavigate(Tab.Bots) }
                Tile(Icons.Default.DateRange, "Sauvegardes", "${cfg.backupChannels.size} dépôt(s)", Modifier.weight(1f)) { onNavigate(Tab.Backups) }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile(Icons.Default.Warning, "Alertes", "Alertes et rapports", Modifier.weight(1f)) { onNavigate(Tab.Alerts) }
                Tile(Icons.Default.Settings, "Réglages", "Personnaliser l'app", Modifier.weight(1f)) { onNavigate(Tab.Settings) }
            }
        }
        if (needsNotifSetup) {
            item {
                Spacer(Modifier.height(12.dp))
                AppCard(Modifier.fillMaxWidth()) {
                    Text("Recevoir les notifications même app fermée", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "BotDesk doit rester connecté à Telegram en arrière-plan et être autorisé à afficher des notifications.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row {
                        TextButton(onClick = {
                            app.store.update { it.copy(backgroundService = true) }
                            app.notifier.syncService()
                            val denied = Build.VERSION.SDK_INT >= 33 &&
                                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            if (denied) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else if (!notifAllowed) {
                                app.lock.skipNextLock = true
                                ctx.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                            }
                        }) { Text("Activer") }
                        TextButton(onClick = { bannerDismissed = true }) { Text("Plus tard") }
                    }
                }
            }
        }
        if (favorites.isNotEmpty()) {
            item { SectionHeader("Favoris") }
            items(favorites, key = { "f" + it.id }) { c -> BotRow(c.id, cfg.bots[c.id]?.displayName ?: c.title, activityLabel(c.lastMessage?.date ?: 0), c.lastMessage?.date ?: 0) { onOpenChat(c.id) } }
        }
        item { SectionHeader("Activité récente", "Voir tout") { onNavigate(Tab.Bots) } }
        if (recent.isEmpty()) item { EmptyState("🤖", "Aucun bot trouvé", "Écrivez à un bot depuis Telegram, puis actualisez dans l'onglet Bots.") }
        items(recent, key = { "r" + it.id }) { c ->
            val last = c.lastMessage
            BotRow(c.id, cfg.bots[c.id]?.displayName ?: c.title, activityLabel(last?.date ?: 0) + (last?.let { " · " + Msg.text(it).take(50) } ?: ""), last?.date ?: 0) { onOpenChat(c.id) }
        }
        item { SectionHeader("Dernières sauvegardes", "Voir tout") { onNavigate(Tab.Backups) } }
        if (cfg.backupChannels.isEmpty()) item { EmptyState("🗄", "Aucun dépôt choisi", "Choisissez vos canaux de sauvegarde dans l'onglet Sauvegardes.") }
        items(lastBackups, key = { "b" + it.second.chatId + "_" + it.second.msgId }) { (title, f) ->
            AppCard(Modifier.fillMaxWidth().padding(vertical = 4.dp), onClick = { onOpenChannel(f.chatId) }, padding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.doc.fileName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Text("$title · ${fmtDate(f.date)} · ${fmtSize(f.doc.document.size)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Spacer(Modifier.width(8.dp))
                    FormatBadge(FormatDetector.guess(f.doc.fileName, f.doc.mimeType))
                }
            }
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
private fun Tile(icon: ImageVector, title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    AppCard(modifier, onClick = onClick) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Accent)
        }
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
fun BotRow(chatId: Long, title: String, subtitle: String, activitySec: Int, onClick: () -> Unit) {
    AppCard(Modifier.fillMaxWidth().padding(vertical = 4.dp), onClick = onClick, padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BotAvatar(chatId, title, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            Spacer(Modifier.width(8.dp))
            ActivityDot(activitySec)
        }
    }
}
