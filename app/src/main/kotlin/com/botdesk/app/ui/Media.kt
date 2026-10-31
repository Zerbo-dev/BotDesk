@file:OptIn(ExperimentalMaterial3Api::class)

package com.botdesk.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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

/** Lecteur audio unique : lancer un son arrête le précédent. */
object AudioController {
    private var mp: MediaPlayer? = null

    /** (identifiant du fichier en cours, en lecture ?) */
    val state = MutableStateFlow(0 to false)

    fun play(path: String, fileId: Int) {
        stop()
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
        state.value = fileId to true
    }

    fun pause() {
        mp?.let { if (it.isPlaying) it.pause() }
        state.value = state.value.first to false
    }

    fun resume() {
        mp?.start()
        state.value = state.value.first to true
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
fun ImageFromFile(file: TdApi.File, mime: String) {
    val app = LocalApp.current
    val open = rememberFileOpener()
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
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).clickable { open(file, mime, "Image") },
        )
    } else if (!state.second) {
        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
    } else {
        TextButton(onClick = { open(file, mime, "Image") }) { Text("Image indisponible — ouvrir") }
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
    val mine = st.first == file.id
    val playing = mine && st.second
    var busy by remember { mutableStateOf(false) }
    var pos by remember { mutableStateOf(0f) }

    LaunchedEffect(playing) {
        while (playing) { pos = AudioController.fraction(); delay(250) }
        if (!mine) pos = 0f
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {
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
        }) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp)) else Text(if (playing) "⏸" else "▶", style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.width(200.dp)) {
            Text(title, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { if (mine) pos else 0f }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            Text(fmtDuration(durationSec) + " · " + fmtSize(maxOf(file.size, file.expectedSize)), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun VideoRow(video: TdApi.Video) {
    val open = rememberFileOpener()
    Column {
        Text("🎞 " + video.fileName.ifBlank { "Vidéo" } + " · " + fmtDuration(video.duration) + " · " + fmtSize(maxOf(video.video.size, video.video.expectedSize)))
        TextButton(onClick = { open(video.video, video.mimeType.ifBlank { "video/mp4" }, video.fileName.ifBlank { "Vidéo" }) }) { Text("Lire") }
    }
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
