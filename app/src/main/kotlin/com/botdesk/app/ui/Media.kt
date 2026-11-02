@file:OptIn(ExperimentalMaterial3Api::class)

package com.botdesk.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import androidx.compose.foundation.Image
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

/** Lecteur audio unique : lancer un son arrête le précédent. Vitesse 1× / 1,5× / 2× et recherche dans la piste. */
object AudioController {
    private var mp: MediaPlayer? = null

    /** (identifiant du fichier en cours, en lecture ?) */
    val state = MutableStateFlow(0 to false)
    val speed = MutableStateFlow(1f)

    private fun applySpeed() {
        val p = mp ?: return
        try { p.playbackParams = p.playbackParams.setSpeed(speed.value) } catch (e: Exception) { }
    }

    fun play(path: String, fileId: Int) {
        stop()
        Speaker.stop()
        val p = MediaPlayer()
        p.setDataSource(path)
        p.setOnCompletionListener {
            state.value = 0 to false
            it.release()
            if (mp === it) mp = null
        }
        p.prepare()
        p.start()
        mp = p
        applySpeed()
        state.value = fileId to true
    }

    fun pause() {
        mp?.let { if (it.isPlaying) it.pause() }
        state.value = state.value.first to false
    }

    fun resume() {
        mp?.start()
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

    fun stop() {
        try { mp?.release() } catch (e: Exception) { }
        mp = null
        state.value = 0 to false
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
fun PhotoView(photo: TdApi.Photo) {
    val sizes = photo.sizes.filterNotNull().sortedBy { it.width }
    val best = sizes.lastOrNull { it.width <= 1280 } ?: sizes.firstOrNull()
    if (best != null) ImageFromFile(best.photo, "image/jpeg")
}

@Composable
fun AudioRow(file: TdApi.File, title: String, durationSec: Int) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by AudioController.state.collectAsState()
    val speed by AudioController.speed.collectAsState()
    val mine = st.first == file.id
    val playing = mine && st.second
    var busy by remember { mutableStateOf(false) }
    var pos by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(playing, dragging) {
        while (playing && !dragging) { pos = AudioController.fraction(); delay(250) }
        if (!mine) pos = 0f
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(Accent).clickable {
                when {
                    playing -> AudioController.pause()
                    mine -> AudioController.resume()
                    !busy -> scope.launch {
                        busy = true
                        try {
                            val d = app.tg.download(file.id)
                            val p = d.local.path
                            if (!d.local.isDownloadingCompleted || p.isNullOrEmpty()) toast(ctx, "Téléchargement incomplet")
                            else AudioController.play(p, file.id)
                        } catch (e: Exception) {
                            toast(ctx, "Lecture impossible : " + e.message)
                        }
                        busy = false
                    }
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            else Text(if (playing) "⏸" else "▶", color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.width(190.dp)) {
            Text(title, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = if (mine) pos else 0f,
                onValueChange = { pos = it; dragging = true },
                onValueChangeFinished = { AudioController.seekTo(pos); dragging = false },
                enabled = mine,
                modifier = Modifier.height(24.dp),
            )
            Text(
                (if (mine) fmtDuration((pos * durationSec).toInt()) + " / " else "") + fmtDuration(durationSec) + " · " + fmtSize(maxOf(file.size, file.expectedSize)),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        TextButton(onClick = { AudioController.cycleSpeed() }, contentPadding = PaddingValues(horizontal = 6.dp)) {
            Text(if (speed == 1f) "1×" else if (speed == 1.5f) "1,5×" else "2×")
        }
    }
}

/** Aperçu d'une vidéo : vignette Telegram (quelques Ko) + bouton lecture ; la vidéo n'est téléchargée qu'au toucher. */
@Composable
fun VideoPreview(video: TdApi.File, thumb: TdApi.Thumbnail?, mime: String, name: String, durationSec: Int) {
    val open = rememberFileOpener()
    val play = { open(video, mime.ifBlank { "video/mp4" }, name.ifBlank { "Vidéo" }) }
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
fun VideoRow(video: TdApi.Video) {
    VideoPreview(video.video, video.thumbnail, video.mimeType, video.fileName, video.duration)
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
