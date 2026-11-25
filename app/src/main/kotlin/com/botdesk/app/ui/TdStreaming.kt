package com.botdesk.app.ui

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.botdesk.app.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.IOException

/**
 * Lecture progressive : ExoPlayer lit directement le cache TDLib pendant que le fichier se télécharge,
 * sans attendre la fin (et se repositionne si l'on saute plus loin).
 */
class TdDataSource(
    private val app: AppContainer,
    private val fileId: Int,
    private val totalSize: Long,
) : BaseDataSource(false) {
    private var dataUri: Uri? = null
    private var position = 0L
    private var remaining = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        dataUri = dataSpec.uri
        transferInitializing(dataSpec)
        position = dataSpec.position
        runBlocking {
            val r = TdApi.DownloadFile()
            r.fileId = fileId
            r.priority = 32
            r.offset = position
            r.limit = 0L
            r.synchronous = false
            app.tg.request(r)
        }
        remaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            totalSize > 0 -> (totalSize - position).coerceAtLeast(0L)
            else -> C.LENGTH_UNSET.toLong()
        }
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val want = if (remaining == C.LENGTH_UNSET.toLong()) length.toLong() else minOf(length.toLong(), remaining)
        val deadline = System.currentTimeMillis() + 45_000L
        while (true) {
            val f = runBlocking { app.tg.getFile(fileId) }
            val l = f.local
            val size = maxOf(f.size, f.expectedSize)
            val avail: Long = when {
                l.isDownloadingCompleted -> (size - position).coerceAtLeast(0L)
                position >= l.downloadOffset && position < l.downloadOffset + l.downloadedPrefixSize ->
                    l.downloadOffset + l.downloadedPrefixSize - position
                else -> 0L
            }
            if (avail > 0L) {
                val n = minOf(want, avail)
                val part = runBlocking {
                    val r = TdApi.ReadFilePart()
                    r.fileId = fileId
                    r.offset = position
                    r.count = n
                    app.tg.request(r)
                }
                val data = part.data
                if (data == null || data.isEmpty()) return C.RESULT_END_OF_INPUT
                System.arraycopy(data, 0, buffer, offset, data.size)
                position += data.size
                if (remaining != C.LENGTH_UNSET.toLong()) remaining -= data.size
                bytesTransferred(data.size)
                return data.size
            }
            if (l.isDownloadingCompleted && position >= size) return C.RESULT_END_OF_INPUT
            if (System.currentTimeMillis() > deadline) throw IOException("Délai dépassé pendant le téléchargement progressif")
            try { Thread.sleep(120) } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Lecture interrompue")
            }
        }
    }

    override fun getUri(): Uri? = dataUri

    override fun close() {
        dataUri = null
        if (opened) { opened = false; transferEnded() }
    }
}

/**
 * Lecteur vidéo/audio avec lecture progressive. Si le fichier est déjà entièrement téléchargé il est lu directement ;
 * si la lecture progressive échoue, repli automatique sur le téléchargement complet.
 */
@Composable
fun StreamPlayerView(
    file: TdApi.File,
    mime: String,
    audio: Boolean,
    placeholder: ImageBitmap? = null,
    onPlayer: (ExoPlayer?) -> Unit = {},
    onDuration: (Long) -> Unit = {},
) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val progress by app.tg.downloads.collectAsState()
    var fallback by remember(file.id) { mutableStateOf(false) }
    var ready by remember(file.id) { mutableStateOf(false) }
    var error by remember(file.id) { mutableStateOf<String?>(null) }
    val exo = remember(file.id) {
        AudioController.stop()
        ExoPlayer.Builder(ctx).build()
    }

    DisposableEffect(exo) {
        onPlayer(exo)
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    ready = true
                    onDuration(exo.duration.coerceAtLeast(0L))
                }
            }
            override fun onPlayerError(e: PlaybackException) {
                if (!fallback) fallback = true else error = "Lecture impossible : " + e.errorCodeName
            }
        }
        exo.addListener(l)
        onDispose { exo.removeListener(l); exo.release(); onPlayer(null) }
    }

    LaunchedEffect(file.id, fallback) {
        try {
            val f = app.tg.getFile(file.id)
            val path = f.local.path
            when {
                f.local.isDownloadingCompleted && !path.isNullOrEmpty() ->
                    exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
                !fallback -> {
                    val size = maxOf(f.size, f.expectedSize)
                    val src = ProgressiveMediaSource.Factory(DataSource.Factory { TdDataSource(app, file.id, size) })
                        .createMediaSource(MediaItem.fromUri(Uri.parse("td://file/" + file.id)))
                    exo.setMediaSource(src)
                }
                else -> {
                    val d = app.tg.download(file.id)
                    val p = d.local.path
                    if (p.isNullOrEmpty()) throw IllegalStateException("téléchargement incomplet")
                    exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(p))))
                }
            }
            exo.prepare()
            exo.playWhenReady = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "Lecture impossible : " + e.message
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    player = exo
                    useController = true
                    if (audio) controllerShowTimeoutMs = 0
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (!ready && error == null) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                placeholder?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                ProgressRing(progress[file.id])
            }
        }
        error?.let { Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp)) }
    }
}

/** Lecteur plein écran d'un fichier vidéo/audio avec mode survol et boutons de saut. */
@Composable
fun StreamMedia(file: TdApi.File, mime: String, audio: Boolean) {
    var player by remember(file.id) { mutableStateOf<ExoPlayer?>(null) }
    var durationMs by remember(file.id) { mutableStateOf(0L) }
    var survol by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }
    player?.let { exo -> SurvolEffect(exo, durationMs, survol, file.id, { label = it }) { exo.pause(); label = "Aperçu terminé" } }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            StreamPlayerView(file, mime, audio, null, { player = it }, { durationMs = it })
        }
        player?.let { exo -> SkipBar(exo, durationMs, survol, { survol = it }, label) }
    }
}
