package com.botdesk.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object ImageCache {
    private val cache = LruCache<String, ImageBitmap>(80)
    fun get(key: String?): ImageBitmap? = if (key == null) null else cache.get(key)
    fun put(key: String, b: ImageBitmap) { cache.put(key, b) }
}

/** Images d'avatar choisies par l'utilisateur : recadrées en carré 256 px et gardées localement. */
object AvatarStore {
    fun file(ctx: Context, chatId: Long): File {
        val dir = File(ctx.filesDir, "avatars")
        dir.mkdirs()
        return File(dir, "$chatId.jpg")
    }

    fun save(ctx: Context, chatId: Long, uri: Uri): Boolean = try {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes == null || bytes.isEmpty()) false else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 1024) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            if (bmp == null) false else {
                val side = minOf(bmp.width, bmp.height)
                val square = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
                val out = Bitmap.createScaledBitmap(square, 256, 256, true)
                file(ctx, chatId).outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                true
            }
        }
    } catch (e: Exception) { false }

    fun delete(ctx: Context, chatId: Long) { file(ctx, chatId).delete() }
}

/**
 * Avatar d'un bot. Priorité : image choisie > emoji choisi > photo de profil Telegram > initiale.
 */
@Composable
fun BotAvatar(chatId: Long, name: String, size: Dp = 44.dp) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val cfg by app.store.config.collectAsState()
    val tick by app.tg.version.collectAsState()
    val meta = cfg.bots[chatId]
    val version = meta?.avatarVersion ?: 0
    val emoji = meta?.avatarEmoji?.takeIf { it.isNotBlank() }
    val photo = remember(tick, chatId) { app.tg.chat(chatId)?.photo?.small }
    val useEmoji = version == 0 && emoji != null
    val key: String? = when {
        version > 0 -> "c:$chatId:$version"
        useEmoji -> null
        photo != null -> "t:${photo.id}"
        else -> null
    }
    val bmp by produceState<ImageBitmap?>(ImageCache.get(key), key) {
        value = ImageCache.get(key) ?: if (key == null) null else withContext(Dispatchers.IO) {
            try {
                val path = if (version > 0) AvatarStore.file(ctx, chatId).path else {
                    val d = app.tg.download(photo!!.id)
                    if (d.local.isDownloadingCompleted) d.local.path else null
                }
                if (path.isNullOrEmpty()) null else decode(path, 256)?.also { ImageCache.put(key, it) }
            } catch (e: Exception) { null }
        }
    }
    val b = bmp
    when {
        b != null -> Image(b, name, Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
        useEmoji -> {
            val hue = ((name.hashCode() and 0x7fffffff) % 360).toFloat()
            Box(Modifier.size(size).clip(CircleShape).background(Color.hsv(hue, 0.35f, 0.9f)), contentAlignment = Alignment.Center) {
                Text(emoji ?: "", fontSize = (size.value * 0.5f).sp)
            }
        }
        else -> Avatar(name, size)
    }
}
