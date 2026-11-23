@file:OptIn(ExperimentalMaterial3Api::class)

package com.botdesk.app.ui

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File

/** Un élément de la galerie (photo ou vidéo) avec sa vignette, affichée tout de suite pendant le téléchargement. */
data class GalleryItem(
    val msgId: Long,
    val isVideo: Boolean,
    val file: TdApi.File,
    val thumb: TdApi.File?,
    val name: String,
    val mime: String,
    val date: Int,
    val caption: String,
    val durationSec: Int,
)

data class GalleryRequest(val chatId: Long, val items: List<GalleryItem>, val index: Int)

fun toGalleryItem(m: TdApi.Message): GalleryItem? = when (val c = m.content) {
    is TdApi.MessagePhoto -> {
        val sizes = c.photo.sizes.filterNotNull().sortedBy { it.width }
        val best = sizes.lastOrNull { it.width <= 2048 } ?: sizes.lastOrNull()
        val small = sizes.firstOrNull()
        best?.let { GalleryItem(m.id, false, it.photo, small?.photo, "Photo", "image/jpeg", m.date, c.caption.text, 0) }
    }
    is TdApi.MessageVideo -> GalleryItem(
        m.id, true, c.video.video, c.video.thumbnail?.file,
        c.video.fileName.ifBlank { "Vidéo" }, c.video.mimeType.ifBlank { "video/mp4" }, m.date, c.caption.text, c.video.duration,
    )
    else -> null
}

suspend fun loadBitmap(app: com.botdesk.app.AppContainer, file: TdApi.File, target: Int): ImageBitmap? = try {
    val d = app.tg.download(file.id)
    val p = d.local.path
    if (d.local.isDownloadingCompleted && !p.isNullOrEmpty()) withContext(Dispatchers.IO) { decode(p, target) } else null
} catch (e: Exception) { null }

/** Anneau de progression du téléchargement (valeur 0..1, ou indéterminé si inconnue). */
@Composable
fun ProgressRing(p: Float?) {
    Box(Modifier.size(68.dp).clip(CircleShape).background(Color(0x77000000)), contentAlignment = Alignment.Center) {
        if (p == null || p <= 0f) {
            CircularProgressIndicator(Modifier.size(42.dp), color = Color.White, strokeWidth = 3.dp)
        } else {
            CircularProgressIndicator(progress = { p }, modifier = Modifier.size(42.dp), color = Color.White, strokeWidth = 3.dp)
            Text("${(p * 100).toInt()}", color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun GalleryPhoto(item: GalleryItem, onTap: () -> Unit) {
    val app = LocalApp.current
    val progress by app.tg.downloads.collectAsState()
    val small by produceState<ImageBitmap?>(null, item.thumb?.id) { value = item.thumb?.let { loadBitmap(app, it, 512) } }
    val full by produceState<ImageBitmap?>(null, item.file.id) { value = loadBitmap(app, item.file, 2048) }
    val f = full
    if (f != null) {
        ZoomableImage(f, onTap)
    } else {
        Box(Modifier.fillMaxSize().clickable { onTap() }, contentAlignment = Alignment.Center) {
            small?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            ProgressRing(progress[item.file.id])
        }
    }
}

@Composable
private fun GalleryVideo(
    item: GalleryItem,
    active: Boolean,
    onPlayer: (ExoPlayer?) -> Unit,
    onDuration: (Long) -> Unit,
    onTap: () -> Unit,
) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val progress by app.tg.downloads.collectAsState()
    val thumb by produceState<ImageBitmap?>(null, item.thumb?.id) { value = item.thumb?.let { loadBitmap(app, it, 720) } }
    var path by remember(item.msgId) { mutableStateOf<String?>(null) }
    var error by remember(item.msgId) { mutableStateOf(false) }

    if (active) {
        LaunchedEffect(item.msgId) {
            try {
                val d = app.tg.download(item.file.id)
                if (d.local.isDownloadingCompleted) path = d.local.path else error = true
            } catch (e: Exception) { error = true }
        }
    }

    val p = path
    if (active && p != null) {
        val exo = remember(p) {
            AudioController.stop()
            ExoPlayer.Builder(ctx).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(File(p))))
                prepare()
                playWhenReady = true
            }
        }
        DisposableEffect(exo) {
            onPlayer(exo)
            val l = object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) onDuration(exo.duration.coerceAtLeast(0L))
                }
            }
            exo.addListener(l)
            onDispose { exo.removeListener(l); exo.release(); onPlayer(null) }
        }
        AndroidView(
            factory = { c -> PlayerView(c).apply { player = exo; useController = true } },
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Box(Modifier.fillMaxSize().clickable { onTap() }, contentAlignment = Alignment.Center) {
            thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            when {
                error -> Text("Lecture impossible", color = Color.White)
                active -> ProgressRing(progress[item.file.id])
                else -> Box(Modifier.size(64.dp).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                    PlayPauseIcon(false, Color.White, 28.dp)
                }
            }
        }
    }
}

/**
 * Galerie photos/vidéos d'une conversation : balayage gauche/droite entre les médias (comme Telegram),
 * zoom, compteur « n sur N », aperçu pendant le téléchargement, et survol/saut sur les vidéos.
 */
@Composable
fun GalleryScreen(req: GalleryRequest, onClose: () -> Unit) {
    val app = LocalApp.current
    val tg = app.tg
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(req.items) }
    val pager = rememberPagerState(initialPage = req.index.coerceIn(0, (req.items.size - 1).coerceAtLeast(0))) { items.size }
    var exhausted by remember { mutableStateOf(false) }
    var loadingOlder by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(true) }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var durationMs by remember { mutableStateOf(0L) }
    var survol by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }
    val page = pager.currentPage
    val cur = items.getOrNull(page)

    // Quand on approche du début, on charge des médias plus anciens sans perdre sa place
    LaunchedEffect(page, items.size) {
        if (page <= 2 && !exhausted && !loadingOlder && items.isNotEmpty()) {
            loadingOlder = true
            try {
                val oldest = items.first().msgId
                var from = oldest
                var found = emptyList<GalleryItem>()
                var tries = 0
                while (found.isEmpty() && tries < 6) {
                    val msgs = tg.history(req.chatId, from, 100)
                    if (msgs.isEmpty()) { exhausted = true; break }
                    found = msgs.mapNotNull { toGalleryItem(it) }.filter { it.msgId < oldest }
                    from = msgs.last().id
                    tries++
                }
                if (found.isNotEmpty()) {
                    val add = found.sortedBy { it.msgId }
                    items = add + items
                    pager.scrollToPage(page + add.size)
                } else if (tries >= 6) exhausted = true
            } catch (e: Exception) { exhausted = true }
            loadingOlder = false
        }
    }

    // Le survol ne s'applique qu'à la vidéo affichée
    LaunchedEffect(cur?.msgId) { survol = false; label = ""; durationMs = 0L }
    player?.let { exo -> SurvolEffect(exo, durationMs, survol && cur?.isVideo == true, cur?.msgId, { label = it }) { exo.pause(); label = "Aperçu terminé" } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { p ->
            val item = items.getOrNull(p)
            if (item != null) {
                if (item.isVideo) GalleryVideo(item, p == pager.currentPage, { player = it }, { durationMs = it }, { chrome = !chrome })
                else GalleryPhoto(item) { chrome = !chrome }
            }
        }

        if (chrome) {
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Fermer", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(cur?.name ?: "", color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(
                        "${page + 1} sur ${items.size}" + (if (!exhausted) "+" else "") + (cur?.let { " · " + fmtDate(it.date) } ?: ""),
                        color = Color(0xCCFFFFFF), style = MaterialTheme.typography.labelSmall,
                    )
                }
                TextButton(onClick = {
                    val c = cur ?: return@TextButton
                    scope.launch {
                        try {
                            val d = tg.download(c.file.id)
                            val path = d.local.path
                            if (d.local.isDownloadingCompleted && !path.isNullOrEmpty()) openExternally(ctx, app, path, c.mime)
                        } catch (e: Exception) { toast(ctx, "Erreur : " + e.message) }
                    }
                }) { Text("Autre app", color = Color.White) }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            if (chrome && !cur?.caption.isNullOrBlank()) {
                Text(
                    cur?.caption ?: "", color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 4,
                    modifier = Modifier.fillMaxWidth().background(Color(0x99000000)).padding(12.dp),
                )
            }
            val exo = player
            if (cur?.isVideo == true && exo != null) SkipBar(exo, durationMs, survol, { survol = it }, label)
        }
    }
}
