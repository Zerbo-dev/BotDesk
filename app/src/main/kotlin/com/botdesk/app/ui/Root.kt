package com.botdesk.app.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.fragment.app.FragmentActivity
import com.botdesk.app.td.AuthUi

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Accueil", Icons.Default.Home),
    Bots("Bots", Icons.Default.Face),
    Backups("Sauvegardes", Icons.Default.DateRange),
    Alerts("Alertes", Icons.Default.Warning),
    Settings("Paramètres", Icons.Default.Settings),
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
            is AuthUi.Starting -> LoadingScreen("Démarrage…")
            is AuthUi.NeedCredentials -> CredentialsScreen()
            is AuthUi.WaitPhone, is AuthUi.WaitCode, is AuthUi.WaitPassword -> LoginScreen(a)
            is AuthUi.Unsupported -> LoadingScreen("Étape de connexion non prise en charge : ${a.state}")
            is AuthUi.Ready -> Shell(activity)
        }
    }
}

@Composable
private fun Shell(activity: FragmentActivity) {
    var tab by remember { mutableStateOf(Tab.Home) }
    var chatId by remember { mutableStateOf<Long?>(null) }
    var channelId by remember { mutableStateOf<Long?>(null) }

    BackHandler(enabled = chatId != null || channelId != null) { chatId = null; channelId = null }

    val c = chatId
    val ch = channelId
    if (c != null) {
        ChatScreen(c) { chatId = null }
    } else if (ch != null) {
        BackupFilesScreen(ch) { channelId = null }
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    Tab.values().forEach { t ->
                        NavigationBarItem(selected = tab == t, onClick = { tab = t }, icon = { Icon(t.icon, t.label) }, label = { Text(t.label) })
                    }
                }
            },
        ) { pad ->
            androidx.compose.foundation.layout.Box(Modifier.padding(pad).fillMaxSize()) {
                when (tab) {
                    Tab.Home -> HomeScreen({ chatId = it }, { channelId = it })
                    Tab.Bots -> BotsScreen { chatId = it }
                    Tab.Backups -> BackupsScreen { channelId = it }
                    Tab.Alerts -> AlertsScreen { chatId = it }
                    Tab.Settings -> SettingsScreen(activity)
                }
            }
        }
    }
}
