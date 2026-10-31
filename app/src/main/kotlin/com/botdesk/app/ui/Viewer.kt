@file:OptIn(ExperimentalMaterial3Api::class)

package com.botdesk.app.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Collections
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/** Demande d'ouverture d'un fichier dans les lecteurs internes. */
data class ViewerRequest(val file: TdApi.File, val name: String, val mime: String)

enum class Kind { Image, Video, Audio, Pdf, Zip, Gz, Text, Unknown }

private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")
private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "mov", "3gp", "avi")
private val AUDIO_EXTS = setOf("mp3", "ogg", "oga", "opus", "m4a", "wav", "flac", "aac")
private val TEXT_EXTS = setOf("txt", "log", "json", "md", "csv", "tsv", "yml", "yaml", "conf", "cfg", "ini", "xml", "sql", "sh", "py", "js", "html", "env", "toml", "out", "err")

private fun looksLikeText(path: String): Boolean = try {
    File(path).inputStream().use { s ->
        val b = ByteArray(4096)
        val n = s.read(b)
        n <= 0 || (0 until n).none { b[it].toInt() == 0 }
    }
} catch (e: Exception) { false }

fun kindOf(name: String, mime: String, path: String): Kind {
    val ext = name.substringAfterLast('.', "").lowercase()
    val sniff = FormatDetector.sniff(path)
    return when {
        sniff == "PDF" || ext == "pdf" || mime == "application/pdf" -> Kind.Pdf
        mime.startsWith("image/") || ext in IMAGE_EXTS -> Kind.Image
        mime.startsWith("video/") || ext in VIDEO_EXTS -> Kind.Video
        mime.startsWith("audio/") || ext in AUDIO_EXTS -> Kind.Audio
        sniff == "ZIP" || ext == "zip" -> Kind.Zip
        sniff == "GZ" || ext == "gz" || ext == "tgz" -> Kind.Gz
        mime.startsWith("text/") || ext in TEXT_EXTS -> Kind.Text
        looksLikeText(path) -> Kind.Text
        else -> Kind.Unknown
    }
}

private data class Loaded(val path: String?, val kind: Kind?, val done: Boolean)

@Composable
fun ViewerScreen(req: ViewerRequest, onClose: () -> Unit) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val loaded by produceState(Loaded(null, null, false), req.file.id) {
        val p = try {
            val d = app.tg.download(req.file.id)
            if (d.local.isDownloadingCompleted) d.local.path else null
        } catch (e: Exception) { null }
        value = if (p.isNullOrEmpty()) Loaded(null, null, true)
        else Loaded(p, withContext(Dispatchers.IO) { kindOf(req.name, req.mime, p) }, true)
    }
    val path = loaded.path

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(req.name, maxLines = 1) },
            navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Fermer") } },
            actions = {
                if (path != null) TextButton(onClick = { openExternally(ctx, app, path, req.mime) }) { Text("Autre app") }
            },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !loaded.done -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(); Spacer(Modifier.height(12.dp)); Text("Téléchargement…")
                }
                path == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Téléchargement impossible.", color = MaterialTheme.colorScheme.error) }
                else -> when (loaded.kind) {
                    Kind.Image -> ImageViewer(path)
                    Kind.Video -> MediaViewer(path, audio = false)
                    Kind.Audio -> MediaViewer(path, audio = true)
                    Kind.Pdf -> PdfViewer(path)
                    Kind.Zip -> ZipViewer(path)
                    Kind.Gz -> TextViewer(path, req.name, gz = true)
                    Kind.Text -> TextViewer(path, req.name, gz = false)
                    else -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Ce format n'a pas de lecteur interne.", style = MaterialTheme.typography.titleSmall)
                        Text("Vous pouvez l'ouvrir avec une autre application (bouton en haut).", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ---------- Image avec zoom ----------

@Composable
fun ZoomableImage(bitmap: ImageBitmap) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero }) }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    offset = if (scale <= 1f) Offset.Zero else offset + pan
                }
            },
    ) {
        Image(
            bitmap = bitmap, contentDescription = "Image", contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
        )
    }
}

@Composable
private fun ImageViewer(path: String) {
    val bmp by produceState<ImageBitmap?>(null, path) { value = withContext(Dispatchers.IO) { try { decode(path, 2048) } catch (e: Exception) { null } } }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) ZoomableImage(b) else CircularProgressIndicator()
    }
}

// ---------- Vidéo et audio (Media3) ----------

@Composable
private fun MediaViewer(path: String, audio: Boolean) {
    val ctx = LocalContext.current
    val exo = remember(path) {
        AudioController.stop()
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(exo) { onDispose { exo.release() } }
    AndroidView(
        factory = { c ->
            PlayerView(c).apply {
                player = exo
                useController = true
                if (audio) controllerShowTimeoutMs = 0
            }
        },
        modifier = Modifier.fillMaxSize().background(Color.Black),
    )
}

// ---------- PDF ----------

@Composable
private fun PdfViewer(path: String) {
    val renderer by produceState<PdfRenderer?>(null, path) {
        value = try { withContext(Dispatchers.IO) { PdfRenderer(ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)) } } catch (e: Exception) { null }
        awaitDispose { try { value?.close() } catch (e: Exception) { } }
    }
    val lock = remember { Mutex() }
    var zoomed by remember { mutableStateOf<ImageBitmap?>(null) }
    val r = renderer
    if (r == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("PDF illisible (protégé par mot de passe ou corrompu).") }
    } else {
        LazyColumn(Modifier.fillMaxSize().background(Color(0xFF303030)), contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(r.pageCount) { i ->
                val page by produceState<ImageBitmap?>(null, i) {
                    value = withContext(Dispatchers.IO) {
                        try {
                            lock.withLock {
                                r.openPage(i).use { p ->
                                    val w = 1400
                                    val h = (w.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                                    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                    b.eraseColor(android.graphics.Color.WHITE)
                                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    b.asImageBitmap()
                                }
                            }
                        } catch (e: Exception) { null }
                    }
                }
                val b = page
                if (b != null) {
                    Image(b, "Page ${i + 1}", Modifier.fillMaxWidth().clickable { zoomed = b }, contentScale = ContentScale.FillWidth)
                } else {
                    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
                }
            }
            item { Text("${r.pageCount} page(s) · toucher une page pour zoomer", color = Color.White, modifier = Modifier.padding(8.dp)) }
        }
    }
    zoomed?.let { z ->
        Dialog(onDismissRequest = { zoomed = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) { ZoomableImage(z) }
        }
    }
}

// ---------- Texte / logs / JSON / GZ ----------

private const val MAX_TEXT = 2_000_000

private fun readLimited(stream: InputStream, limit: Int = MAX_TEXT): Pair<String, Boolean> {
    return stream.use { s ->
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(16384)
        var total = 0
        var truncated = false
        while (true) {
            val n = s.read(chunk)
            if (n < 0) break
            if (total + n > limit) {
                out.write(chunk, 0, limit - total)
                truncated = true
                break
            }
            out.write(chunk, 0, n)
            total += n
        }
        String(out.toByteArray(), Charsets.UTF_8) to truncated
    }
}

private fun isBinary(t: String): Boolean {
    val head = t.take(4000)
    return head.any { it == '\u0000' } || (head.isNotEmpty() && head.count { it == '\uFFFD' } * 10 > head.length)
}

private data class TextData(val lines: List<String>?, val truncated: Boolean, val message: String?)

@Composable
private fun TextViewer(path: String, name: String, gz: Boolean) {
    val data by produceState(TextData(null, false, null), path) {
        value = try {
            withContext(Dispatchers.IO) {
                val raw: InputStream = File(path).inputStream().let { if (gz) GZIPInputStream(it) else it }
                val (text, truncated) = readLimited(raw)
                if (isBinary(text)) {
                    TextData(null, false, if (gz) "Archive compressée au contenu non textuel (par ex. .tar.gz) : pas d'aperçu texte." else "Contenu binaire : non affichable comme texte.")
                } else {
                    var shown = text
                    if (!truncated && name.endsWith(".json", true)) {
                        try { shown = if (text.trimStart().startsWith("[")) JSONArray(text).toString(2) else JSONObject(text).toString(2) } catch (e: Exception) { }
                    }
                    TextData(shown.lines(), truncated, null)
                }
            }
        } catch (e: Exception) { TextData(null, false, "Lecture impossible : " + e.message) }
    }
    var filter by remember { mutableStateOf("") }
    val lines = data.lines
    when {
        data.message != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(data.message ?: "") }
        lines == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> {
            val shown = remember(lines, filter) { if (filter.isBlank()) lines else lines.filter { it.contains(filter, true) } }
            Column(Modifier.fillMaxSize()) {
                OutlinedTextField(filter, { filter = it }, label = { Text("Filtrer les lignes") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(8.dp))
                Text(
                    "${shown.size} ligne(s)" + if (data.truncated) " · affichage limité aux premiers 2 Mo" else "",
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 12.dp),
                )
                LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    items(shown.size) { i -> Text(shown[i], fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                }
            }
        }
    }
}

// ---------- Archive ZIP (liste du contenu, lecture seule) ----------

private data class ZipItem(val name: String, val size: Long, val packed: Long, val dir: Boolean)

@Composable
private fun ZipViewer(path: String) {
    val entries by produceState<List<ZipItem>?>(null, path) {
        value = withContext(Dispatchers.IO) {
            try {
                ZipFile(path).use { z ->
                    Collections.list(z.entries()).take(2000).map { ZipItem(it.name, it.size, it.compressedSize, it.isDirectory) }
                }
            } catch (e: Exception) { null }
        }
    }
    var preview by remember { mutableStateOf<Pair<String, String>?>(null) }
    val scope = rememberCoroutineScope()
    val list = entries
    if (list == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Archive illisible ou chiffrée.") }
    } else {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            item { Text("${list.size} entrée(s) · toucher un fichier texte pour l'aperçu", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 8.dp)) }
            items(list, key = { it.name }) { e ->
                val ext = e.name.substringAfterLast('.', "").lowercase()
                val previewable = !e.dir && ext in TEXT_EXTS && e.size in 0L..1_000_000L
                Column(Modifier.fillMaxWidth().clickable(enabled = previewable) {
                    scope.launch(Dispatchers.IO) {
                        try {
                            ZipFile(path).use { z ->
                                val ze = z.getEntry(e.name)
                                val (t, _) = readLimited(z.getInputStream(ze), 200_000)
                                preview = e.name to t
                            }
                        } catch (ex: Exception) { }
                    }
                }.padding(vertical = 6.dp)) {
                    Text((if (e.dir) "📁 " else "") + e.name, style = MaterialTheme.typography.bodyMedium)
                    if (!e.dir) Text(fmtSize(e.size) + " (compressé : " + fmtSize(e.packed) + ")", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    preview?.let { (n, t) ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(n) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(t, fontFamily = FontFamily.Monospace, fontSize = 12.sp) } },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("Fermer") } },
        )
    }
}
