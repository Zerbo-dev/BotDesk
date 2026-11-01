package com.botdesk.app.ui

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import com.botdesk.app.core.SecureStore
import com.botdesk.app.data.AppConfig
import com.botdesk.app.data.LocalStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * Lecture vocale des messages.
 * - Moteur « system » : synthèse du téléphone (voix, vitesse et hauteur réglables).
 * - Moteur « eleven » : voix ElevenLabs avec la clé API de l'utilisateur (le texte lu est envoyé à ElevenLabs).
 */
object Speaker {
    data class VoiceInfo(val name: String, val label: String, val quality: Int, val network: Boolean, val language: String)

    private var tts: TextToSpeech? = null
    private var ready = false
    private var store: LocalStore? = null
    private var secure: SecureStore? = null
    private val waiting = mutableListOf<() -> Unit>()
    private var player: MediaPlayer? = null
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    /** Identifiant du message en cours de lecture (0 = aucun). */
    val speakingId = MutableStateFlow(0L)

    /** Voix installées sur le téléphone (rempli une fois le moteur prêt). */
    val voices = MutableStateFlow<List<VoiceInfo>>(emptyList())

    fun bind(store: LocalStore, secure: SecureStore) {
        this.store = store
        this.secure = secure
    }

    private fun clean(text: String): String =
        text.replace(Regex("https?://\\S+"), " lien ")
            .replace(Regex("[*_`#>~]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(3900)

    private fun toastMain(ctx: Context, msg: String) {
        main.post { Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_LONG).show() }
    }

    private fun ensure(ctx: Context, then: () -> Unit) {
        if (ready) { then(); return }
        waiting += then
        if (tts == null) {
            tts = TextToSpeech(ctx.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) { speakingId.value = 0L }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { speakingId.value = 0L }
                    })
                    voices.value = try {
                        tts?.voices.orEmpty()
                            .map { VoiceInfo(it.name, it.locale.displayName + " · " + it.name, it.quality, it.isNetworkConnectionRequired, it.locale.language) }
                            .sortedWith(compareByDescending<VoiceInfo> { it.quality }.thenBy { it.label })
                    } catch (e: Exception) { emptyList() }
                    waiting.toList().forEach { it() }
                } else {
                    toastMain(ctx, "Synthèse vocale indisponible sur ce téléphone")
                }
                waiting.clear()
            }
        }
    }

    /** Initialise le moteur sans parler (pour lister les voix dans les réglages). */
    fun prepare(ctx: Context) = ensure(ctx) { }

    fun speak(ctx: Context, text: String, id: Long, lang: String) {
        val cfg = store?.config?.value ?: AppConfig()
        val clean = clean(text)
        if (clean.isBlank()) return
        stop()
        if (cfg.ttsEngine == "eleven") speakEleven(ctx, clean, id, cfg)
        else ensure(ctx) { speakSystem(clean, id, lang, cfg) }
    }

    private fun speakSystem(text: String, id: Long, lang: String, cfg: AppConfig) {
        val t = tts ?: return
        val locale = when (lang) {
            "fr" -> Locale.FRENCH
            "en" -> Locale.ENGLISH
            else -> Locale.getDefault()
        }
        t.setLanguage(locale)
        if (cfg.ttsVoice.isNotBlank()) {
            t.voices?.firstOrNull { it.name == cfg.ttsVoice }?.let { v -> t.setVoice(v) }
        }
        t.setSpeechRate(cfg.ttsRate.coerceIn(0.5f, 2f))
        t.setPitch(cfg.ttsPitch.coerceIn(0.5f, 1.5f))
        speakingId.value = id
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id.toString())
    }

    // ---------- ElevenLabs ----------

    private fun sha(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    private fun fetchEleven(text: String, key: String, voiceId: String): ByteArray {
        val c = URL("https://api.elevenlabs.io/v1/text-to-speech/$voiceId?output_format=mp3_44100_128").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.doOutput = true
        c.setRequestProperty("xi-api-key", key)
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "audio/mpeg")
        val body = JSONObject().put("text", text).put("model_id", "eleven_multilingual_v2").toString()
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        if (c.responseCode !in 200..299) {
            val err = c.errorStream?.bufferedReader()?.readText().orEmpty()
            throw IOException("code ${c.responseCode} ${err.take(160)}")
        }
        return c.inputStream.use { it.readBytes() }
    }

    private fun speakEleven(ctx: Context, text: String, id: Long, cfg: AppConfig) {
        val key = secure?.getString("eleven_key")
        if (key.isNullOrBlank() || cfg.elevenVoiceId.isBlank()) {
            toastMain(ctx, "ElevenLabs : clé API ou identifiant de voix manquant (Réglages > Voix)")
            return
        }
        speakingId.value = id
        io.launch {
            try {
                val dir = ctx.cacheDir
                val file = File(dir, "tts_" + sha(cfg.elevenVoiceId + "|" + text) + ".mp3")
                if (!file.exists()) {
                    file.writeBytes(fetchEleven(text.take(2500), key, cfg.elevenVoiceId))
                    dir.listFiles { f -> f.name.startsWith("tts_") }?.sortedBy { it.lastModified() }?.dropLast(40)?.forEach { it.delete() }
                }
                main.post { if (speakingId.value == id) playFile(file, cfg.ttsRate) }
            } catch (e: Exception) {
                main.post { speakingId.value = 0L }
                toastMain(ctx, "Voix premium indisponible : " + e.message)
            }
        }
    }

    private fun playFile(file: File, rate: Float) {
        releasePlayer()
        try {
            val p = MediaPlayer()
            p.setDataSource(file.absolutePath)
            p.setOnCompletionListener {
                speakingId.value = 0L
                it.release()
                if (player === it) player = null
            }
            p.prepare()
            p.start()
            try { p.playbackParams = p.playbackParams.setSpeed(rate.coerceIn(0.5f, 2f)) } catch (e: Exception) { }
            player = p
        } catch (e: Exception) {
            speakingId.value = 0L
        }
    }

    private fun releasePlayer() {
        try { player?.release() } catch (e: Exception) { }
        player = null
    }

    fun stop() {
        tts?.stop()
        releasePlayer()
        speakingId.value = 0L
    }
}
