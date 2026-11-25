@file:OptIn(ExperimentalMaterial3Api::class)

package com.botdesk.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.random.Random

/** Positions (ms) des extraits : « répartis » = régulièrement espacés ; « aléatoires » = tirés au hasard puis joués dans l'ordre. */
fun buildSkipPlan(durationMs: Long, segMs: Long, extracts: Int, random: Boolean): List<Long> {
    val maxStart = (durationMs - segMs).coerceAtLeast(0L)
    if (extracts <= 1) return listOf(maxStart / 2)
    if (!random) return (0 until extracts).map { maxStart * it / (extracts - 1) }
    val chunks = (maxStart / segMs).toInt() + 1
    return (0 until chunks).shuffled().take(extracts).sorted().map { (it * segMs).coerceAtMost(maxStart) }
}

/**
 * Mode « survol » : on ne regarde que quelques extraits d'un média long.
 * Le temps est compté sur la position de lecture : une pause suspend l'extrait en cours.
 */
@Composable
fun SurvolEffect(
    player: ExoPlayer,
    durationMs: Long,
    enabled: Boolean,
    key: Any?,
    onLabel: (String) -> Unit,
    onDone: () -> Unit,
) {
    val app = LocalApp.current
    val cfg by app.store.config.collectAsState()
    LaunchedEffect(key, enabled, durationMs, cfg.skipSegSec, cfg.skipExtracts, cfg.skipRandom) {
        if (!enabled || durationMs <= 0L) { onLabel(""); return@LaunchedEffect }
        val seg = cfg.skipSegSec * 1000L
        if (durationMs <= seg * 2) { onLabel("Média court : lu en entier"); return@LaunchedEffect }
        val plan = buildSkipPlan(durationMs, seg, cfg.skipExtracts, cfg.skipRandom)
        for ((i, start) in plan.withIndex()) {
            onLabel("Extrait ${i + 1}/${plan.size}")
            player.seekTo(start)
            player.playWhenReady = true
            val end = start + seg
            delay(500)
            while (isActive && player.currentPosition < end && player.playbackState != Player.STATE_ENDED) delay(200)
            if (player.playbackState == Player.STATE_ENDED) break
        }
        if (isActive) { onLabel(""); onDone() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipsRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (v, l) -> FilterChip(selected == v, { onSelect(v) }, { Text(l) }) }
    }
}

/** Boutons de saut (−10 s, +10 s, saut aléatoire) et réglage du mode survol. */
@Composable
fun SkipBar(player: ExoPlayer, durationMs: Long, enabled: Boolean, onEnabled: (Boolean) -> Unit, label: String) {
    val app = LocalApp.current
    val cfg by app.store.config.collectAsState()
    var settings by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L)) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("−10 s") }
            OutlinedButton(
                onClick = {
                    val limit = if (durationMs > 0) durationMs else Long.MAX_VALUE
                    player.seekTo((player.currentPosition + 10_000L).coerceAtMost(limit))
                },
                contentPadding = PaddingValues(horizontal = 10.dp),
            ) { Text("+10 s") }
            OutlinedButton(
                onClick = { if (durationMs > 3000L) player.seekTo(Random.nextLong(0L, durationMs - 3000L)) },
                contentPadding = PaddingValues(horizontal = 10.dp),
            ) { Text("🎲 Saut") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(enabled, { onEnabled(!enabled) }, { Text("Survol") })
            Spacer(Modifier.width(8.dp))
            Text(
                if (enabled && label.isNotBlank()) label
                else "${cfg.skipExtracts} extraits de ${cfg.skipSegSec} s, " + if (cfg.skipRandom) "aléatoires" else "répartis",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { settings = true }) { Text("Réglages") }
        }
    }

    if (settings) {
        AlertDialog(
            onDismissRequest = { settings = false },
            title = { Text("Mode survol") },
            text = {
                Column {
                    Text("Au lieu de tout regarder, la lecture saute d'extrait en extrait.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Durée d'un extrait", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    ChipsRow(listOf("5" to "5 s", "10" to "10 s", "15" to "15 s", "30" to "30 s"), cfg.skipSegSec.toString()) { v -> app.store.update { it.copy(skipSegSec = v.toInt()) } }
                    Text("Nombre d'extraits", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    ChipsRow(listOf("3" to "3", "5" to "5", "8" to "8", "12" to "12"), cfg.skipExtracts.toString()) { v -> app.store.update { it.copy(skipExtracts = v.toInt()) } }
                    Text("Choix des extraits", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    ChipsRow(listOf("random" to "Aléatoires", "even" to "Répartis"), if (cfg.skipRandom) "random" else "even") { v -> app.store.update { it.copy(skipRandom = v == "random") } }
                }
            },
            confirmButton = { TextButton(onClick = { settings = false }) { Text("OK") } },
        )
    }
}
