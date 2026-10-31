package com.botdesk.app.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

/** Lecture vocale des messages avec la synthèse vocale du téléphone (rien n'est envoyé à BotDesk). */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: Triple<String, Long, Locale>? = null

    /** Identifiant du message en cours de lecture (0 = aucun). */
    val speakingId = MutableStateFlow(0L)

    private fun clean(text: String): String =
        text.replace(Regex("https?://\\S+"), " lien ")
            .replace(Regex("[*_`#>~]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(3900)

    fun speak(ctx: Context, text: String, id: Long, lang: String) {
        val locale = when (lang) {
            "fr" -> Locale.FRENCH
            "en" -> Locale.ENGLISH
            else -> Locale.getDefault()
        }
        val clean = clean(text)
        if (clean.isBlank()) return
        if (tts == null) {
            pending = Triple(clean, id, locale)
            tts = TextToSpeech(ctx.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) { speakingId.value = 0L }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { speakingId.value = 0L }
                    })
                    pending?.let { (t, i, l) -> doSpeak(t, i, l) }
                }
                pending = null
            }
        } else if (ready) {
            doSpeak(clean, id, locale)
        } else {
            pending = Triple(clean, id, locale)
        }
    }

    private fun doSpeak(text: String, id: Long, locale: Locale) {
        val t = tts ?: return
        t.setLanguage(locale)
        speakingId.value = id
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id.toString())
    }

    fun stop() {
        tts?.stop()
        speakingId.value = 0L
    }
}
