@file:OptIn(ExperimentalMaterial3Api::class)

package com.botdesk.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import kotlin.random.Random
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.botdesk.app.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File

fun fmtDuration(sec: Int): String = String.format("%d:%02d", sec / 60, sec % 60)

/** Un morceau de la file de lecture (audios et messages vocaux d'une conversation). */
data class QueueItem(
    val msgId: Long,
    val file: TdApi.File,
    val title: String,
    val subtitle: String,
    val cover: TdApi.File?,
    val durationSec: Int,
)

fun toQueueItem(m: TdApi.Message): QueueItem? = when (val c = m.content) {
    is TdApi.MessageAudio -> QueueItem(
        m.id, c.audio.audio, c.audio.title.ifBlank { c.audio.fileName }.ifBlank { "Audio" },
        c.audio.performer, c.audio.albumCoverThumbnail?.file, c.audio.duration,
    )
    is TdApi.MessageVoiceNote -> QueueItem(m.id, c.voiceNote.voice, "Message vocal", "", null, c.voiceNote.duration)
    else -> null
}

val LocalAudioQueue = staticCompositionLocalOf<List<QueueItem>> { emptyList() }

/** Ouvre la galerie sur un message (photo/vidéo) ; fourni par la conversation. */
val LocalMediaOpener = staticCompositionLocalOf<((Long) -> Unit)?> { null }

/**
 * Lecteur audio global : continue quand on quitte la conversation (et écran éteint), enchaîne les morceaux
 * de la conversation, avec répétition, aléatoire, ordre inversé, vitesse et recherche dans la piste.
 */
object AudioController {
    private var mp: MediaPlayer? = null
    private var app: com.botdesk.app.AppContainer? = null
    private var queue: List<QueueItem> = emptyList()
    private var token = 0L
    private val main = Handler(Looper.getMainLooper())

    /** (identifiant du fichier en cours, en lecture ?) */
    val state = MutableStateFlow(0 to false)
    val speed = MutableStateFlow(1f)
    val now = MutableStateFlow<QueueItem?>(null)
    val loadingId = MutableStateFlow(0)
    /** 0 = aucune, 1 = ce morceau, 2 = toute la liste */
    val repeat = MutableStateFlow(0)
    val shuffle = MutableStateFlow(false)
    val reverse = MutableStateFlow(false)

    fun bind(a: com.botdesk.app.AppContainer) { app = a }

    private fun say(msg: String) {
        val a = app ?: return
        main.post { Toast.makeText(a.app, msg, Toast.LENGTH_LONG).show() }
    }

    private fun applySpeed() {
        val p = mp ?: return
        try { p.playbackParams = p.playbackParams.setSpeed(speed.value) } catch (e: Exception) { }
    }

    /** Démarre un morceau (le télécharge si besoin) et mémorise la file pour enchaîner automatiquement. */
    fun start(item: QueueItem, newQueue: List<QueueItem>) {
        val a = app ?: return
        queue = if (newQueue.any { it.file.id == item.file.id }) newQueue else listOf(item)
        now.value = item
        loadingId.value = item.file.id
        val t = ++token
        a.scope.launch {
            try {
                val d = a.tg.download(item.file.id)
                val p = d.local.path
                if (t != token) return@launch
                if (!d.local.isDownloadingCompleted || p.isNullOrEmpty()) {
                    say("Téléchargement incomplet")
                    loadingId.value = 0
                    return@launch
                }
                withContext(Dispatchers.Main) { if (t == token) play(p, item.file.id) }
            } catch (e: Exception) {
                say("Lecture impossible : " + e.message)
                if (t == token) loadingId.value = 0
            }
        }
    }

    private fun play(path: String, fileId: Int) {
        releasePlayer()
        Speaker.stop()
        try {
            val p = MediaPlayer()
            app?.let { p.setWakeMode(it.app, PowerManager.PARTIAL_WAKE_LOCK) }
            p.setDataSource(path)
            p.setOnCompletionListener { onCompleted(it) }
            p.setOnErrorListener { _, _, _ -> say("Erreur de lecture"); stop(); true }
            p.prepare()
            p.start()
            mp = p
            applySpeed()
            state.value = fileId to true
        } catch (e: Exception) {
            say("Lecture impossible : " + e.message)
            stop()
        }
        loadingId.value = 0
    }

    private fun onCompleted(p: MediaPlayer) {
        val cur = now.value
        try { p.release() } catch (e: Exception) { }
        if (mp !== p) return
        mp = null
        state.value = 0 to false
        if (cur == null) return
        if (repeat.value == 1) { start(cur, queue); return }
        val i = queue.indexOfFirst { it.file.id == cur.file.id }
        nextIndex(i, 1)?.let { start(queue[it], queue) }
    }

    private fun nextIndex(i: Int, dir: Int): Int? {
        if (queue.isEmpty()) return null
        if (shuffle.value && queue.size > 1) {
            var r: Int
            do { r = Random.nextInt(queue.size) } while (r == i)
            return r
        }
        val step = if (reverse.value) -dir else dir
        val n = i + step
        return when {
            n in queue.indices -> n
            repeat.value == 2 -> (n + queue.size) % queue.size
            else -> null
        }
    }

    fun next() {
        val cur = now.value ?: return
        nextIndex(queue.indexOfFirst { it.file.id == cur.file.id }, 1)?.let { start(queue[it], queue) }
    }

    fun previous() {
        val cur = now.value ?: return
        val p = mp
        if (p != null && p.currentPosition > 3000) { seekTo(0f); return }
        nextIndex(queue.indexOfFirst { it.file.id == cur.file.id }, -1)?.let { start(queue[it], queue) }
    }

    fun toggle() {
        val cur = now.value ?: return
        when {
            state.value.second -> pause()
            mp != null -> resume()
            else -> start(cur, queue)
        }
    }

    fun pause() {
        mp?.let { if (it.isPlaying) it.pause() }
        state.value = state.value.first to false
    }

    fun resume() {
        val p = mp ?: return
        p.start()
        applySpeed()
        state.value = state.value.first to true
    }

    fun cycleSpeed() {
        speed.value = when (speed.value) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }
        if (state.value.second) applySpeed()
    }

    fun seekTo(fraction: Float) {
        val p = mp ?: return
        p.seekTo((p.duration * fraction.coerceIn(0f, 1f)).toInt())
    }

    fun fraction(): Float {
        val p = mp ?: return 0f
        val d = p.duration
        return if (d > 0) p.currentPosition.toFloat() / d else 0f
    }

    private fun releasePlayer() {
        try { mp?.release() } catch (e: Exception) { }
        mp = null
    }

    fun stop() {
        token++
        loadingId.value = 0
        releasePlayer()
        state.value = 0 to false
        now.value = null
    }
}

/** Ouvre un fichier téléchargé dans une application externe (via FileProvider, sans permission). */
fun openExternally(ctx: Context, app: AppContainer, path: String, mime: String) {
    try {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", File(path))
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime.ifBlank { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        app.lock.skipNextLock = true
        ctx.startActivity(view)
    } catch (e: ActivityNotFoundException) {
        app.lock.skipNextLock = false
        toast(ctx, "Aucune application pour ouvrir ce type de fichier")
    } catch (e: IllegalArgumentException) {
        toast(ctx, "Fichier introuvable dans le stockage de l'app")
    }
}

/** Télécharge puis ouvre un fichier dans les lecteurs internes ; confirmation au-dessus du seuil de taille réglé. */
@Composable
fun rememberFileOpener(): (TdApi.File, String, String) -> Unit {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg by app.store.config.collectAsState()
    var big by remember { mutableStateOf<ViewerRequest?>(null) }

    fun go(req: ViewerRequest) {
        scope.launch {
            try {
                if (!req.file.local.isDownloadingCompleted) toast(ctx, "Téléchargement en cours…")
                AudioController.stop()
                app.viewer.value = req
            } catch (e: Exception) {
                toast(ctx, "Erreur : " + e.message)
            }
        }
    }

    big?.let { r ->
        AlertDialog(
            onDismissRequest = { big = null },
            title = { Text("Gros fichier") },
            text = { Text("« ${r.name} » fait ${fmtSize(maxOf(r.file.size, r.file.expectedSize))}. Le télécharger consomme des données mobiles. Continuer ?") },
            confirmButton = { TextButton(onClick = { big = null; go(r) }) { Text("Télécharger") } },
            dismissButton = { TextButton(onClick = { big = null }) { Text("Annuler") } },
        )
    }

    return { f, mime, name ->
        val req = ViewerRequest(f, name, mime)
        val limit = cfg.confirmDownloadMb * 1_000_000L
        if (limit > 0 && maxOf(f.size, f.expectedSize) > limit && !f.local.isDownloadingCompleted) big = req else go(req)
    }
}

internal fun decode(path: String, target: Int = 1080): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / sample > target * 2) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
}

@Composable
fun ImageFromFile(file: TdApi.File, mime: String, onClick: (() -> Unit)? = null) {
    val app = LocalApp.current
    val open = rememberFileOpener()
    val click: () -> Unit = onClick ?: { open(file, mime, "Image") }
    val state by produceState<Pair<ImageBitmap?, Boolean>>(null to false, file.id) {
        val bmp = try {
            val d = app.tg.download(file.id)
            val p = d.local.path
            if (d.local.isDownloadingCompleted && !p.isNullOrEmpty()) withContext(Dispatchers.IO) { decode(p) } else null
        } catch (e: Exception) { null }
        value = bmp to true
    }
    val bmp = state.first
    if (bmp != null) {
        Image(
            bitmap = bmp, contentDescription = "Image", contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().aspectRatio((bmp.width.toFloat() / bmp.height).coerceIn(0.75f, 2.4f)).clip(RoundedCornerShape(12.dp)).clickable { click() },
        )
    } else if (!state.second) {
        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
    } else {
        TextButton(onClick = { click() }) { Text("Aperçu indisponible — ouvrir") }
    }
}

@Composable
fun PhotoView(photo: TdApi.Photo, msgId: Long = 0L) {
    val opener = LocalMediaOpener.current
    val sizes = photo.sizes.filterNotNull().sortedBy { it.width }
    val best = sizes.lastOrNull { it.width <= 1280 } ?: sizes.firstOrNull()
    if (best != null) {
        ImageFromFile(best.photo, "image/jpeg", onClick = if (opener != null && msgId != 0L) ({ opener(msgId) }) else null)
    }
}

/**
 * Lecteur audio de conversation. Musique : pochette, titre, artiste, curseur. Message vocal : forme d'onde touchable.
 * Les couleurs s'adaptent à la bulle (envoyée = fond bleu, reçue = fond sombre/clair).
 */
@Composable
fun AudioRow(
    file: TdApi.File,
    title: String,
    durationSec: Int,
    outgoing: Boolean = false,
    performer: String = "",
    cover: TdApi.Thumbnail? = null,
    waveform: ByteArray? = null,
) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by AudioController.state.collectAsState()
    val speed by AudioController.speed.collectAsState()
    val mine = st.first == file.id
    val playing = mine && st.second
    val loading by AudioController.loadingId.collectAsState()
    val queue = LocalAudioQueue.current
    val busy = loading == file.id
    var pos by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    val onColor = if (outgoing) Color.White else MaterialTheme.colorScheme.onSurface
    val accent = if (outgoing) Color.White else Accent
    val buttonBg = if (outgoing) Color.White else Accent
    val buttonFg = if (outgoing) Accent else Color.White

    LaunchedEffect(playing, dragging) {
        while (playing && !dragging) { pos = AudioController.fraction(); delay(250) }
        if (!mine) pos = 0f
    }

    fun toggle() {
        when {
            busy -> Unit
            playing -> AudioController.pause()
            mine -> AudioController.resume()
            else -> AudioController.start(QueueItem(0L, file, title, performer, cover?.file, durationSec), queue)
        }
    }

    Row(Modifier.fillMaxWidth().widthIn(min = 250.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(52.dp).clip(if (cover != null) RoundedCornerShape(12.dp) else CircleShape)
                .background(buttonBg).clickable { toggle() },
            contentAlignment = Alignment.Center,
        ) {
            if (cover != null) ImageFromFile(cover.file, "image/jpeg", onClick = { toggle() })
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(if (cover != null) Color(0x99000000) else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = if (cover != null) Color.White else buttonFg, strokeWidth = 2.dp)
                else PlayPauseIcon(playing, if (cover != null) Color.White else buttonFg, 20.dp)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = onColor, style = MaterialTheme.typography.titleSmall)
            if (performer.isNotBlank()) {
                Text(performer, maxLines = 1, overflow = TextOverflow.Ellipsis, color = onColor.copy(alpha = 0.75f), style = MaterialTheme.typography.labelMedium)
            }
            if (waveform != null && waveform.isNotEmpty()) {
                Waveform(
                    decodeWaveform(waveform), if (mine) pos else 0f, accent, accent.copy(alpha = 0.35f),
                    onSeek = { f -> if (mine) { AudioController.seekTo(f); pos = f } },
                )
            } else {
                Slider(
                    value = if (mine) pos else 0f,
                    onValueChange = { pos = it; dragging = true },
                    onValueChangeFinished = { AudioController.seekTo(pos); dragging = false },
                    enabled = mine,
                    colors = SliderDefaults.colors(
                        thumbColor = accent,
                        activeTrackColor = accent,
                        inactiveTrackColor = accent.copy(alpha = 0.3f),
                        disabledThumbColor = accent.copy(alpha = 0.55f),
                        disabledActiveTrackColor = accent.copy(alpha = 0.55f),
                        disabledInactiveTrackColor = accent.copy(alpha = 0.3f),
                    ),
                    modifier = Modifier.height(28.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (mine) fmtDuration((pos * durationSec).toInt()) + " / " else "") + fmtDuration(durationSec),
                    color = onColor.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.weight(1f))
                Text(fmtSize(maxOf(file.size, file.expectedSize)), color = onColor.copy(alpha = 0.65f), style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, onColor.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                .clickable { AudioController.cycleSpeed() }.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(if (speed == 1f) "1×" else if (speed == 1.5f) "1,5×" else "2×", color = onColor, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Aperçu d'une vidéo : vignette Telegram (quelques Ko) + bouton lecture ; la vidéo n'est téléchargée qu'au toucher. */
@Composable
fun VideoPreview(video: TdApi.File, thumb: TdApi.Thumbnail?, mime: String, name: String, durationSec: Int, onOpen: (() -> Unit)? = null) {
    val open = rememberFileOpener()
    val play: () -> Unit = onOpen ?: { open(video, mime.ifBlank { "video/mp4" }, name.ifBlank { "Vidéo" }) }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { play() }) {
        if (thumb != null) {
            ImageFromFile(thumb.file, "image/jpeg", onClick = { play() })
        } else {
            Box(Modifier.fillMaxWidth().height(160.dp).background(Color(0xFF1A2F52)))
        }
        Box(Modifier.align(Alignment.Center).size(54.dp).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
            Text("▶", color = Color.White, style = MaterialTheme.typography.titleLarge)
        }
        Box(Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text(
                (if (durationSec > 0) fmtDuration(durationSec) + " · " else "") + fmtSize(maxOf(video.size, video.expectedSize)),
                color = Color.White, style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
fun VideoRow(video: TdApi.Video, msgId: Long = 0L) {
    val opener = LocalMediaOpener.current
    VideoPreview(video.video, video.thumbnail, video.mimeType, video.fileName, video.duration, if (opener != null && msgId != 0L) ({ opener(msgId) }) else null)
}

@Composable
fun DocumentRow(doc: TdApi.Document, onSave: () -> Unit) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val open = rememberFileOpener()
    var preview by remember { mutableStateOf<String?>(null) }
    val size = maxOf(doc.document.size, doc.document.expectedSize)
    val handler = PreviewRegistry.find(doc.fileName, doc.mimeType, size)

    if (doc.mimeType.startsWith("image/") && size in 1L..15_000_000L) ImageFromFile(doc.document, doc.mimeType)
    else if (doc.mimeType.startsWith("video/") && doc.thumbnail != null) VideoPreview(doc.document, doc.thumbnail, doc.mimeType, doc.fileName, 0)
    Text("📎 " + doc.fileName + " · " + fmtSize(size))
    Row {
        TextButton(onClick = { open(doc.document, doc.mimeType.ifBlank { "*/*" }, doc.fileName) }) { Text("Lire / ouvrir") }
        if (handler != null) {
            TextButton(onClick = {
                scope.launch {
                    try {
                        val d = app.tg.download(doc.document.id)
                        preview = withContext(Dispatchers.IO) { handler.preview(d.local.path) }
                    } catch (e: Exception) { toast(ctx, "Aperçu impossible : " + e.message) }
                }
            }) { Text("Aperçu") }
        }
        TextButton(onClick = onSave) { Text("Enregistrer") }
    }
    preview?.let { text ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(doc.fileName) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(text, style = MaterialTheme.typography.bodySmall) } },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("Fermer") } },
        )
    }
}
