package com.botdesk.app.td

import org.drinkless.tdlib.TdApi

object Msg {
    private fun withCaption(prefix: String, caption: TdApi.FormattedText?): String =
        listOf(prefix, caption?.text.orEmpty()).filter { it.isNotBlank() }.joinToString("\n")

    fun text(m: TdApi.Message): String = when (val c = m.content) {
        is TdApi.MessageText -> c.text.text
        is TdApi.MessageDocument -> withCaption("📎 " + c.document.fileName, c.caption)
        is TdApi.MessagePhoto -> withCaption("🖼 Photo", c.caption)
        is TdApi.MessageVideo -> withCaption("🎞 Vidéo", c.caption)
        is TdApi.MessageAudio -> withCaption("🎵 Audio", c.caption)
        is TdApi.MessageVoiceNote -> withCaption("🎙 Message vocal", c.caption)
        else -> "[" + c.javaClass.simpleName.removePrefix("Message") + "]"
    }

    /** Légende d'un média ; null si le message n'est pas un média (on affiche alors [text]). */
    fun caption(m: TdApi.Message): String? = when (val c = m.content) {
        is TdApi.MessagePhoto -> c.caption.text
        is TdApi.MessageDocument -> c.caption.text
        is TdApi.MessageVideo -> c.caption.text
        is TdApi.MessageAudio -> c.caption.text
        is TdApi.MessageVoiceNote -> c.caption.text
        else -> null
    }

    fun document(m: TdApi.Message): TdApi.Document? = (m.content as? TdApi.MessageDocument)?.document
}
