package com.botdesk.app.ui

import org.drinkless.tdlib.TdApi

/** Un résultat de recherche inline d'un bot (article, audio, document, photo ou vidéo). */
data class InlineItem(val queryId: Long, val id: String, val title: String, val subtitle: String)

fun toInlineItems(r: TdApi.InlineQueryResults): List<InlineItem> =
    (r.results?.filterNotNull() ?: emptyList()).mapNotNull { x ->
        when (x) {
            is TdApi.InlineQueryResultArticle -> InlineItem(r.inlineQueryId, x.id, x.title, x.description)
            is TdApi.InlineQueryResultAudio -> InlineItem(r.inlineQueryId, x.id, x.audio.title.ifBlank { x.audio.fileName }, x.audio.performer)
            is TdApi.InlineQueryResultDocument -> InlineItem(r.inlineQueryId, x.id, x.title.ifBlank { x.document.fileName }, x.description)
            is TdApi.InlineQueryResultPhoto -> InlineItem(r.inlineQueryId, x.id, x.title.ifBlank { "Photo" }, x.description)
            is TdApi.InlineQueryResultVideo -> InlineItem(r.inlineQueryId, x.id, x.title.ifBlank { "Vidéo" }, x.description)
            else -> null
        }
    }
