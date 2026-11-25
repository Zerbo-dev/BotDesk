@file:OptIn(ExperimentalMaterial3Api::class)

package com.botdesk.app.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File

/** Lecture automatique des médias d'une conversation ou d'un canal, dans l'un ou l'autre sens. */
data class AutoPlayRequest(val chatId: Long, val forward: Boolean, val startId: Long?)

enum class PKind { Video, Audio, Photo }

data class PlayItem(
    val msgId: Long,
    val kind: PKind,
    val file: TdApi.File,
    val title: String,
    val durationSec: Int,
    val date: Int,
)

fun toPlayItem(m: TdApi.Message): PlayItem? = when (val c = m.content) {
    is TdApi.MessageVideo -> PlayItem(m.id, PKind.Video, c.video.video, c.video.fileName.ifBlank { c.caption.text.take(60) }.ifBlank { "Vidéo" }, c.video.duration, m.date)
    is TdApi.MessageVideoNote -> PlayItem(m.id, PKind.Video, c.videoNote.video, "Message vidéo", c.videoNote.duration, m.date)
    is TdApi.MessageAudio -> PlayItem(
        m.id, PKind.Audio, c.audio.audio,
        listOf(c.audio.performer, c.audio.title.ifBlank { c.audio.fileName }).filter { it.isNotBlank() }.joinToString(" — ").ifBlank { "Audio" },
        c.audio.duration, m.date,
    )
    is TdApi.MessageVoiceNote -> PlayItem(m.id, PKind.Audio, c.voiceNote.voice, "Message vocal", c.voiceNote.duration, m.date)
    is TdApi.MessageDocument -> {
        val mt = c.document.mimeType
        when {
            mt.startsWith("video/") -> PlayItem(m.id, PKind.Video, c.document.document, c.document.fileName, 0, m.date)
            mt.startsWith("audio/") -> PlayItem(m.id, PKind.Audio, c.document.document, c.document.fileName, 0, m.date)
            else -> null
        }
    }
    is TdApi.MessagePhoto -> {
        val sizes = c.photo.sizes.filterNotNull().sortedBy { it.width }
        val best = sizes.lastOrNull { it.width <= 1280 } ?: sizes.firstOrNull()
        best?.let { PlayItem(m.id, PKind.Photo, it.photo, c.caption.text.take(60).ifBlank { "Photo" }, 0, m.date) }
    }
    else -> null
}

@Composable
fun AutoPlayScreen(req: AutoPlayRequest, onClose: () -> Unit) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val tg = app.tg
    val scope = rememberCoroutineScope()
    val cfg by app.store.config.collectAsState()
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }

    var all by remember { mutableStateOf<List<PlayItem>>(emptyList()) }
    var scanned by remember { mutableStateOf(0) }
    var firstPage by remember { mutableStateOf(false) }
    var loadDone by remember { mutableStateOf(false) }
    var forward by remember { mutableStateOf(req.forward) }
    var useVideo by remember { mutableStateOf(true) }
    var useAudio by remember { mutableStateOf(true) }
    var usePhoto by remember { mutableStateOf(false) }
    var currentId by remember { mutableStateOf<Long?>(null) }
    var started by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var survol by remember { mutableStateOf(false) }
    var segLabel by remember { mutableStateOf("") }
    var durationMs by remember { mutableStateOf(0L) }
    var photo by remember { mutableStateOf<ImageBitmap?>(null) }
    var lastAdvanced by remember { mutableStateOf(-1L) }
    var showSettings by remember { mutableStateOf(false) }

    val list = remember(all, useVideo, useAudio, usePhoto) {
        all.filter { when (it.kind) { PKind.Video -> useVideo; PKind.Audio -> useAudio; PKind.Photo -> usePhoto } }.sortedBy { it.msgId }
    }
    val listRef by rememberUpdatedState(list)
    val forwardRef by rememberUpdatedState(forward)
    val loopRef by rememberUpdatedState(cfg.autoLoop)
    val current = list.firstOrNull { it.msgId == currentId }
    val index = list.indexOfFirst { it.msgId == currentId }

    val player = remember { ExoPlayer.Builder(ctx).build() }

    fun goTo(i: Int) {
        val l = listRef
        when {
            i in l.indices -> { currentId = l[i].msgId; status = "" }
            loopRef && l.isNotEmpty() -> { lastAdvanced = -1L; currentId = if (i < 0) l.last().msgId else l.first().msgId }
            else -> status = if (i < 0) "Début atteint" else "Fin de la lecture"
        }
    }

    fun advance() {
        val cur = currentId ?: return
        if (lastAdvanced == cur) return
        lastAdvanced = cur
        val i = listRef.indexOfFirst { it.msgId == cur }
        goTo(i + if (forwardRef) 1 else -1)
    }

    fun step(delta: Int) {
        val cur = currentId ?: return
        val i = listRef.indexOfFirst { it.msgId == cur }
        goTo(i + delta * (if (forwardRef) 1 else -1))
    }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) durationMs = player.duration.coerceAtLeast(0L)
                if (state == Player.STATE_ENDED) advance()
            }
            override fun onPlayerError(error: PlaybackException) {
                status = "Erreur de lecture : " + error.errorCodeName
                advance()
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }

    // Chargement de l'historique (jusqu'à 2000 messages), par pages de 100 du plus récent au plus ancien
    LaunchedEffect(req.chatId) {
        var from = 0L
        var pages = 0
        val acc = mutableListOf<PlayItem>()
        while (pages < 20) {
            val page = try { tg.history(req.chatId, from, 100) } catch (e: Exception) { break }
            if (page.isEmpty()) break
            page.mapNotNull { toPlayItem(it) }.let { acc += it }
            from = page.last().id
            pages++
            scanned += page.size
            all = acc.toList()
            if (pages == 1) firstPage = true
        }
        loadDone = true
    }

    // Choix du premier média : « du plus récent » démarre dès la première page ; les autres attendent la fin du chargement
    LaunchedEffect(firstPage, loadDone, list.size) {
        if (started) return@LaunchedEffect
        val ready = if (req.startId == null && !req.forward) firstPage else loadDone
        if (!ready) return@LaunchedEffect
        val l = list
        if (l.isEmpty()) { if (loadDone) status = "Aucun média lisible trouvé"; return@LaunchedEffect }
        val sid = req.startId
        val start = when {
            sid != null && req.forward -> l.firstOrNull { it.msgId >= sid } ?: l.last()
            sid != null -> l.lastOrNull { it.msgId <= sid } ?: l.first()
            req.forward -> l.first()
            else -> l.last()
        }
        currentId = start.msgId
        started = true
    }

    // Changement de types (vidéos/audios/photos) : on reste sur le média courant ou le plus proche
    LaunchedEffect(list) {
        if (!started) return@LaunchedEffect
        if (list.none { it.msgId == currentId }) {
            val cur = currentId ?: return@LaunchedEffect
            val nxt = if (forwardRef) list.firstOrNull { it.msgId >= cur } else list.lastOrNull { it.msgId <= cur }
            if (nxt != null) currentId = nxt.msgId
        }
    }

    // Lecture du média courant
    LaunchedEffect(currentId) {
        val item = listRef.firstOrNull { it.msgId == currentId } ?: return@LaunchedEffect
        durationMs = 0L
        segLabel = ""
        photo = null
        AudioController.stop()
        Speaker.stop()
        try {
            val d = tg.download(item.file.id)
            val p = d.local.path
            if (!d.local.isDownloadingCompleted || p.isNullOrEmpty()) throw IllegalStateException("téléchargement incomplet")
            if (item.kind == PKind.Photo) {
                player.pause()
                photo = withContext(Dispatchers.IO) { decode(p, 2048) }
                delay(cfg.autoPhotoSec * 1000L)
                advance()
            } else {
                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(p))))
                player.prepare()
                player.playWhenReady = true
                val l = listRef
                val nx = l.getOrNull(l.indexOf(item) + if (forwardRef) 1 else -1)
                if (nx != null) scope.launch { try { tg.download(nx.file.id) } catch (e: Exception) { } }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            status = "Impossible de lire « ${item.title} » : ${e.message}"
            delay(1500)
            advance()
        }
    }

    SurvolEffect(player, durationMs, survol && current?.kind != PKind.Photo, currentId, { segLabel = it }) { advance() }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        TopAppBar(
            title = {
                Column {
                    Text(current?.title ?: "Lecture automatique", maxLines = 1, style = MaterialTheme.typography.titleSmall)
                    Text(
                        (if (index >= 0) "${index + 1} / ${list.size}" else "") +
                            (if (!loadDone) " · $scanned messages analysés" else "") +
                            (if (segLabel.isNotBlank()) " · $segLabel" else ""),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Fermer") } },
            actions = { TextButton(onClick = { showSettings = true }) { Text("Réglages") } },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                current == null -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!loadDone || status.isBlank()) CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (status.isNotBlank()) status else "Recherche des médias… $scanned messages analysés",
                        color = Color.White, textAlign = TextAlign.Center,
                    )
                }
                current.kind == PKind.Photo -> {
                    val b = photo
                    if (b != null) ZoomableImage(b) else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                else -> AndroidView(
                    factory = { c ->
                        PlayerView(c).apply {
                            this.player = player
                            useController = true
                            if (current.kind == PKind.Audio) controllerShowTimeoutMs = 0
                        }
                    },
                    update = { it.controllerShowTimeoutMs = if (current.kind == PKind.Audio) 0 else 3000 },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (status.isNotBlank() && current != null) {
                Text(
                    status, color = Color.White, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }

        SkipBar(player, durationMs, survol, { survol = it }, segLabel)
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 10.dp).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { step(-1) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("⏮") }
            OutlinedButton(onClick = { step(1) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("⏭") }
            FilterChip(
                selected = true, onClick = { forward = !forward; lastAdvanced = -1L },
                label = { Text(if (forward) "Sens : vers les plus récents" else "Sens : vers les plus anciens") },
            )
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Lecture automatique") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Vidéos", Modifier.weight(1f)); Switch(useVideo, { useVideo = it }) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Audios et messages vocaux", Modifier.weight(1f)); Switch(useAudio, { useAudio = it }) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Photos (diaporama)", Modifier.weight(1f)); Switch(usePhoto, { usePhoto = it }) }
                    Text("Durée d'une photo", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(3, 5, 10).forEach { s -> FilterChip(cfg.autoPhotoSec == s, { app.store.update { it.copy(autoPhotoSec = s) } }, { Text("$s s") }) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Recommencer à la fin", Modifier.weight(1f)); Switch(cfg.autoLoop, { on -> app.store.update { it.copy(autoLoop = on) } })
                    }
                    Text(
                        "Seuls les ${if (loadDone) "médias trouvés" else "médias déjà analysés"} sont lus : jusqu'à 2000 messages sont analysés.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showSettings = false }) { Text("OK") } },
        )
    }
}
