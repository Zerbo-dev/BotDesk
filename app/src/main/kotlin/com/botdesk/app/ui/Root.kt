package com.botdesk.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.fragment.app.FragmentActivity
import com.botdesk.app.td.AuthUi

enum class Tab(val label: String, val icon: ImageVector) {
    Home("Accueil", Icons.Default.Home),
    Bots("Bots", Icons.Default.Face),
    Backups("Sauvegardes", Icons.Default.DateRange),
    Alerts("Alertes", Icons.Default.Warning),
    Settings("Réglages", Icons.Default.Settings),
}

@Composable
fun Root(activity: FragmentActivity) {
    val app = LocalApp.current
    val locked by app.lock.locked.collectAsState()
    val auth by app.tg.auth.collectAsState()

    if (locked) {
        LockScreen(activity)
    } else {
        when (val a = auth) {
            is AuthUi.Starting -> SplashScreen()
            is AuthUi.NeedCredentials -> CredentialsScreen()
            is AuthUi.WaitPhone, is AuthUi.WaitCode, is AuthUi.WaitPassword -> LoginScreen(a)
            is AuthUi.Unsupported -> LoadingScreen("Étape de connexion non prise en charge : ${a.state}")
            is AuthUi.Ready -> Shell(activity)
        }
    }
}

@Composable
private fun Shell(activity: FragmentActivity) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val flags = ctx.getSharedPreferences("botdesk_flags", Context.MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && !flags.getBoolean("notif_asked", false) &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            flags.edit().putBoolean("notif_asked", true).apply()
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    var tab by remember { mutableStateOf(Tab.Home) }
    var chatId by remember { mutableStateOf<Long?>(null) }
    var channelId by remember { mutableStateOf<Long?>(null) }
    var showHistory by remember { mutableStateOf(false) }

    // Ouverture depuis une notification
    val pending by app.pendingChat.collectAsState()
    LaunchedEffect(pending) {
        pending?.let { id ->
            showHistory = false; channelId = null
            if (app.tg.chat(id)?.let { app.tg.isBotChat(it) } == false) channelId = id else chatId = id
            app.pendingChat.value = null
        }
    }

    BackHandler(enabled = chatId != null || channelId != null || showHistory) {
        chatId = null; channelId = null; showHistory = false
    }
    val viewer by app.viewer.collectAsState()
    BackHandler(enabled = viewer != null) { app.viewer.value = null }

    val openSource: (Long) -> Unit = { id ->
        val chat = app.tg.chat(id)
        showHistory = false
        if (chat != null && app.tg.isBotChat(chat)) chatId = id else channelId = id
    }

    val c = chatId
    val ch = channelId
    val v = viewer
    if (v != null) {
        ViewerScreen(v) { app.viewer.value = null }
    } else if (c != null) {
        ChatScreen(c, onBack = { chatId = null })
    } else if (ch != null) {
        ChannelHost(ch) { channelId = null }
    } else if (showHistory) {
        HistoryScreen(onOpen = openSource) { showHistory = false }
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    Tab.values().forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, t.label) },
                            label = { Text(t.label, maxLines = 1) },
                            alwaysShowLabel = false,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Accent, selectedTextColor = Accent,
                                indicatorColor = Accent.copy(alpha = 0.2f),
                            ),
                        )
                    }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (tab) {
                    Tab.Home -> HomeScreen({ chatId = it }, { channelId = it }) { tab = it }
                    Tab.Bots -> BotsScreen { chatId = it }
                    Tab.Backups -> BackupsScreen({ channelId = it }) { showHistory = true }
                    Tab.Alerts -> AlertsScreen { chatId = it }
                    Tab.Settings -> SettingsScreen(activity)
                }
            }
        }
    }
}

/** Un canal s'ouvre sur ses messages (lecture seule) ; le bouton « Fichiers » bascule vers la liste des fichiers. */
@Composable
private fun ChannelHost(id: Long, onBack: () -> Unit) {
    val app = LocalApp.current
    var files by remember(id) { mutableStateOf(false) }
    val isChannel = app.tg.chat(id)?.let { app.tg.channelKind(it) == "Canal" } ?: true
    if (files) BackupFilesScreen(id, onBack = { files = false }, onMessages = { files = false })
    else ChatScreen(id, onBack = onBack, readOnly = isChannel, onFiles = { files = true })
}
