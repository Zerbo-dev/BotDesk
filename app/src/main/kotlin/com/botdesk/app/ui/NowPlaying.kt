@file:OptIn(ExperimentalMaterial3Api::class)

package com.botdesk.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Barre de lecture en haut de l'app tant qu'un audio est en cours : titre, lecture/pause, fermer ; un toucher ouvre le lecteur complet. */
@Composable
fun MiniPlayer(onOpen: () -> Unit) {
    val now by AudioController.now.collectAsState()
    val st by AudioController.state.collectAsState()
    val loading by AudioController.loadingId.collectAsState()
    now?.let { n ->
        val playing = st.second && st.first == n.file.id
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).clickable(onClick = onOpen).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Accent).clickable { AudioController.toggle() }, contentAlignment = Alignment.Center) {
                if (loading == n.file.id) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                else PlayPauseIcon(playing, Color.White, 18.dp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(n.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (n.subtitle.isNotBlank()) Text(n.subtitle, style = MaterialTheme.typography.labelSmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { AudioController.stop() }) { Text("✕", style = MaterialTheme.typography.titleMedium) }
        }
    }
}

/** Lecteur complet : pochette en arrière-plan floutée, curseur, précédent/suivant, répétition, aléatoire, ordre inversé, vitesse. */
@Composable
fun NowPlayingScreen(onClose: () -> Unit) {
    val app = LocalApp.current
    val n by AudioController.now.collectAsState()
    val st by AudioController.state.collectAsState()
    val speed by AudioController.speed.collectAsState()
    val repeat by AudioController.repeat.collectAsState()
    val shuffle by AudioController.shuffle.collectAsState()
    val reverse by AudioController.reverse.collectAsState()
    val item = n

    LaunchedEffect(item == null) { if (item == null) onClose() }

    if (item != null) {
        val playing = st.second && st.first == item.file.id
        var pos by remember { mutableStateOf(0f) }
        var dragging by remember { mutableStateOf(false) }
        LaunchedEffect(item.file.id, dragging) {
            while (!dragging) { pos = AudioController.fraction(); delay(250) }
        }
        val cover by produceState<ImageBitmap?>(null, item.cover?.id) { value = item.cover?.let { loadBitmap(app, it, 512) } }

        Box(Modifier.fillMaxSize().background(Color(0xFF0E1726))) {
            cover?.let { Image(it, null, Modifier.fillMaxSize().blur(36.dp), contentScale = ContentScale.Crop, alpha = 0.55f) }
            Box(Modifier.fillMaxSize().background(Color(0x66000000)))
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Réduire", tint = Color.White) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { AudioController.stop() }) { Text("Fermer le lecteur", color = Color.White) }
                }
                Spacer(Modifier.weight(1f))
                val art = cover
                if (art != null) {
                    Image(art, item.title, Modifier.size(280.dp).clip(RoundedCornerShape(22.dp)), contentScale = ContentScale.Crop)
                } else {
                    Box(Modifier.size(280.dp).clip(RoundedCornerShape(22.dp)).background(Color(0x33FFFFFF)), contentAlignment = Alignment.Center) {
                        Text("♪", color = Color.White, fontSize = 96.sp)
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text(item.title, color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (item.subtitle.isNotBlank()) Text(item.subtitle, color = Color(0xCCFFFFFF), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Spacer(Modifier.height(12.dp))
                Slider(
                    value = pos,
                    onValueChange = { pos = it; dragging = true },
                    onValueChangeFinished = { AudioController.seekTo(pos); dragging = false },
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color(0x55FFFFFF)),
                )
                Row(Modifier.fillMaxWidth()) {
                    val total = item.durationSec
                    Text(fmtDuration((pos * total).toInt()), color = Color(0xCCFFFFFF), style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    Text(fmtDuration(total), color = Color(0xCCFFFFFF), style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    Text("🔀", fontSize = 24.sp, modifier = Modifier.clickable { AudioController.shuffle.value = !shuffle }.padding(8.dp), color = if (shuffle) Color.White else Color(0x66FFFFFF))
                    Text("⏮", fontSize = 30.sp, color = Color.White, modifier = Modifier.clickable { AudioController.previous() }.padding(8.dp))
                    Box(Modifier.size(72.dp).clip(CircleShape).background(Color.White).clickable { AudioController.toggle() }, contentAlignment = Alignment.Center) {
                        PlayPauseIcon(playing, Color(0xFF0E1726), 32.dp)
                    }
                    Text("⏭", fontSize = 30.sp, color = Color.White, modifier = Modifier.clickable { AudioController.next() }.padding(8.dp))
                    Text(if (repeat == 1) "🔂" else "🔁", fontSize = 24.sp, modifier = Modifier.clickable { AudioController.repeat.value = (repeat + 1) % 3 }.padding(8.dp), color = if (repeat == 0) Color(0x66FFFFFF) else Color.White)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(true, { AudioController.cycleSpeed() }, { Text(if (speed == 1f) "Vitesse 1×" else if (speed == 1.5f) "Vitesse 1,5×" else "Vitesse 2×") })
                    FilterChip(reverse, { AudioController.reverse.value = !reverse }, { Text("Ordre inversé") })
                }
                Text(
                    when (repeat) { 1 -> "Répète ce morceau"; 2 -> "Répète la liste"; else -> "Enchaîne les morceaux de la conversation" },
                    color = Color(0xAAFFFFFF), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.weight(1f))
            }
        }
    }
}
