package com.botdesk.app.data

import android.content.Context
import com.botdesk.app.td.BackupFile
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class IndexedFile(
    val chatId: Long,
    val msgId: Long,
    val chatTitle: String,
    val name: String,
    val mime: String,
    val size: Long,
    val date: Int,
    val sender: String,
    val caption: String,
    val seenAt: Long,
    val downloaded: Boolean,
)

fun BackupFile.toIndexed(chatTitle: String, downloaded: Boolean = false) = IndexedFile(
    chatId, msgId, chatTitle, doc.fileName, doc.mimeType, doc.document.size, date, sender, caption,
    System.currentTimeMillis(), downloaded,
)

/** Index local des fichiers consultés (jamais le contenu des fichiers, seulement leurs métadonnées). */
class FileIndex(context: Context, private val retentionDays: () -> Int) {
    private val file = File(context.filesDir, "file_index.json")
    val items = MutableStateFlow(load())

    private fun key(i: IndexedFile) = i.chatId.toString() + "_" + i.msgId

    private fun load(): List<IndexedFile> = try {
        if (!file.exists()) emptyList() else {
            val a = JSONArray(file.readText())
            (0 until a.length()).map { n ->
                val o = a.getJSONObject(n)
                IndexedFile(
                    o.getLong("chatId"), o.getLong("msgId"), o.optString("chatTitle"), o.optString("name"),
                    o.optString("mime"), o.optLong("size"), o.optInt("date"), o.optString("sender"),
                    o.optString("caption"), o.optLong("seenAt"), o.optBoolean("downloaded"),
                )
            }
        }
    } catch (e: Exception) { emptyList() }

    private fun save(list: List<IndexedFile>) {
        val a = JSONArray()
        list.forEach { i ->
            a.put(JSONObject().apply {
                put("chatId", i.chatId); put("msgId", i.msgId); put("chatTitle", i.chatTitle)
                put("name", i.name); put("mime", i.mime); put("size", i.size); put("date", i.date)
                put("sender", i.sender); put("caption", i.caption); put("seenAt", i.seenAt); put("downloaded", i.downloaded)
            })
        }
        file.writeText(a.toString())
    }

    private fun prune(list: List<IndexedFile>): List<IndexedFile> {
        val days = retentionDays()
        val kept = if (days > 0) {
            val limit = System.currentTimeMillis() - days * 86_400_000L
            list.filter { it.seenAt >= limit }
        } else list
        return kept.sortedByDescending { it.date }.take(3000)
    }

    @Synchronized
    fun record(newItems: List<IndexedFile>) {
        if (newItems.isEmpty()) return
        val map = LinkedHashMap<String, IndexedFile>()
        items.value.forEach { map[key(it)] = it }
        newItems.forEach { n ->
            val old = map[key(n)]
            map[key(n)] = n.copy(downloaded = n.downloaded || old?.downloaded == true)
        }
        val out = prune(map.values.toList())
        items.value = out
        save(out)
    }

    @Synchronized
    fun markDownloaded(chatId: Long, msgId: Long) {
        val out = items.value.map { if (it.chatId == chatId && it.msgId == msgId) it.copy(downloaded = true) else it }
        items.value = out
        save(out)
    }

    @Synchronized
    fun clear() {
        items.value = emptyList()
        save(emptyList())
    }

    @Synchronized
    fun applyRetention() {
        val out = prune(items.value)
        items.value = out
        save(out)
    }
}
