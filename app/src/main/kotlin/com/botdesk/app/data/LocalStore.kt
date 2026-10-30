package com.botdesk.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

const val DEFAULT_ALERT_KEYWORDS = "alert,alerte,erreur,error,critical,critique,failed,échec,echec,down"
const val DEFAULT_SENSITIVE_KEYWORDS = "restart,reboot,stop,shutdown,delete,remove,restore,reset,kill,redémarr,arrêt,supprim,restaur"

data class Category(val id: String, val name: String)

data class BotMeta(
    val categoryId: String? = null,
    val favorite: Boolean = false,
    val displayName: String? = null,
    val description: String? = null,
)

data class AppConfig(
    val categories: List<Category> = emptyList(),
    val bots: Map<Long, BotMeta> = emptyMap(),
    val backupChannels: Set<Long> = emptySet(),
    val alertKeywords: String = DEFAULT_ALERT_KEYWORDS,
    val sensitiveKeywords: String = DEFAULT_SENSITIVE_KEYWORDS,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("app", "botdesk")
        put("version", 1)
        put("categories", JSONArray().also { a ->
            categories.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name)) }
        })
        put("bots", JSONObject().also { o ->
            bots.forEach { (id, m) ->
                o.put(id.toString(), JSONObject().apply {
                    put("favorite", m.favorite)
                    m.categoryId?.let { put("categoryId", it) }
                    m.displayName?.let { put("displayName", it) }
                    m.description?.let { put("description", it) }
                })
            }
        })
        put("backupChannels", JSONArray().also { a -> backupChannels.forEach { a.put(it) } })
        put("alertKeywords", alertKeywords)
        put("sensitiveKeywords", sensitiveKeywords)
    }

    companion object {
        private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null

        fun fromJson(o: JSONObject): AppConfig {
            require(o.str("app") == "botdesk") { "Ce fichier n'est pas une configuration BotDesk" }
            val cats = mutableListOf<Category>()
            o.optJSONArray("categories")?.let { a ->
                for (i in 0 until a.length()) {
                    val c = a.getJSONObject(i)
                    cats += Category(c.getString("id"), c.getString("name"))
                }
            }
            val bots = mutableMapOf<Long, BotMeta>()
            o.optJSONObject("bots")?.let { b ->
                for (k in b.keys()) {
                    val m = b.getJSONObject(k)
                    bots[k.toLong()] = BotMeta(
                        categoryId = m.str("categoryId"),
                        favorite = m.optBoolean("favorite", false),
                        displayName = m.str("displayName"),
                        description = m.str("description"),
                    )
                }
            }
            val ch = mutableSetOf<Long>()
            o.optJSONArray("backupChannels")?.let { a -> for (i in 0 until a.length()) ch += a.getLong(i) }
            return AppConfig(
                categories = cats,
                bots = bots,
                backupChannels = ch,
                alertKeywords = o.str("alertKeywords") ?: DEFAULT_ALERT_KEYWORDS,
                sensitiveKeywords = o.str("sensitiveKeywords") ?: DEFAULT_SENSITIVE_KEYWORDS,
            )
        }
    }
}

/** Configuration locale (catégories, favoris, canaux de sauvegarde, mots-clés), stockée en JSON. */
class LocalStore(context: Context) {
    private val file = File(context.filesDir, "botdesk_config.json")
    val config = MutableStateFlow(load())

    private fun load(): AppConfig = try {
        if (file.exists()) AppConfig.fromJson(JSONObject(file.readText())) else AppConfig()
    } catch (e: Exception) {
        AppConfig()
    }

    @Synchronized
    fun update(f: (AppConfig) -> AppConfig) {
        val n = f(config.value)
        config.value = n
        file.writeText(n.toJson().toString())
    }

    fun replace(c: AppConfig) = update { c }
}
