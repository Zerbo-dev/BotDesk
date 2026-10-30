package com.botdesk.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity

@Composable
fun LockScreen(activity: FragmentActivity) {
    val app = LocalApp.current
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val bio = remember { app.lock.biometricEnabled && Biometric.available(activity) }

    LaunchedEffect(Unit) {
        if (bio) {
            app.lock.skipNextLock = false
            Biometric.prompt(activity) { app.lock.unlock() }
        }
    }

    fun press(d: String) {
        if (pin.length >= 4) return
        pin += d
        if (pin.length == 4) {
            if (app.lock.verify(pin)) {
                app.lock.unlock()
            } else {
                val wait = app.lock.blockedSeconds()
                error = if (wait > 0) "Trop d'essais. Réessayez dans $wait s." else "PIN incorrect"
                pin = ""
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.Lock, contentDescription = null, tint = Accent, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text("Entrez votre PIN", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(4) { i ->
                Box(
                    Modifier.size(14.dp).clip(CircleShape)
                        .background(if (i < pin.length) Accent else MaterialTheme.colorScheme.surfaceVariant)
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(error ?: " ", color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))

        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))
        rows.forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                r.forEach { k -> Key(k) { press(k) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            if (bio) Key("👆") { Biometric.prompt(activity) { app.lock.unlock() } } else Spacer(Modifier.size(72.dp))
            Key("0") { press("0") }
            Key("⌫") { if (pin.isNotEmpty()) pin = pin.dropLast(1) }
        }
    }
}

@Composable
private fun Key(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.headlineSmall) }
}
