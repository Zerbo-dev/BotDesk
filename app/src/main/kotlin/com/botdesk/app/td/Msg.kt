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
        else -> "[" + c.javaClass.simpleName.removePrefix("Message") + "]"
    }

    fun document(m: TdApi.Message): TdApi.Document? = (m.content as? TdApi.MessageDocument)?.document
}
