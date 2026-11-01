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
    val muted: Boolean = false,
    val hidden: Boolean = false,
    val pinnedCommands: List<String> = emptyList(),
    /** 0 = pas d'image choisie ; incrémenté à chaque nouvelle image (invalide le cache). */
    val avatarVersion: Int = 0,
    val avatarEmoji: String? = null,
)

data class AppConfig(
    val categories: List<Category> = emptyList(),
    val bots: Map<Long, BotMeta> = emptyMap(),
    val backupChannels: Set<Long> = emptySet(),
    val alertKeywords: String = DEFAULT_ALERT_KEYWORDS,
    val sensitiveKeywords: String = DEFAULT_SENSITIVE_KEYWORDS,
    /** "off" | "alerts" | "all" */
    val notifyBots: String = "alerts",
    val notifyBackups: Boolean = true,
    val backgroundService: Boolean = false,
    /** "dark" | "light" | "system" */
    val theme: String = "dark",
    /** Demander confirmation avant de télécharger un fichier plus gros (en Mo). 0 = jamais. */
    val confirmDownloadMb: Int = 100,
    /** Durée de conservation de l'historique des fichiers (jours). 0 = illimitée (plafonnée). */
    val indexRetentionDays: Int = 90,
    /** Langue de dictée et de lecture : "auto" | "fr" | "en". */
    val voiceLang: String = "auto",
    val voiceAutoSend: Boolean = false,
    val voiceAutoRead: Boolean = false,
    /** "system" (voix du téléphone) | "eleven" (ElevenLabs, clé API de l'utilisateur). */
    val ttsEngine: String = "system",
    val ttsVoice: String = "",
    val ttsRate: Float = 1f,
    val ttsPitch: Float = 1f,
    val elevenVoiceId: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("app", "botdesk")
        put("version", 2)
        put("categories", JSONArray().also { a ->
            categories.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name)) }
        })
        put("bots", JSONObject().also { o ->
            bots.forEach { (id, m) ->
                o.put(id.toString(), JSONObject().apply {
                    put("favorite", m.favorite)
                    put("muted", m.muted)
                    put("hidden", m.hidden)
                    put("avatarVersion", m.avatarVersion)
                    m.avatarEmoji?.let { put("avatarEmoji", it) }
                    put("pinnedCommands", JSONArray().also { a -> m.pinnedCommands.forEach { a.put(it) } })
                    m.categoryId?.let { put("categoryId", it) }
                    m.displayName?.let { put("displayName", it) }
                    m.description?.let { put("description", it) }
                })
            }
        })
        put("backupChannels", JSONArray().also { a -> backupChannels.forEach { a.put(it) } })
        put("alertKeywords", alertKeywords)
        put("sensitiveKeywords", sensitiveKeywords)
        put("notifyBots", notifyBots)
        put("notifyBackups", notifyBackups)
        put("backgroundService", backgroundService)
        put("theme", theme)
        put("confirmDownloadMb", confirmDownloadMb)
        put("indexRetentionDays", indexRetentionDays)
        put("voiceLang", voiceLang)
        put("voiceAutoSend", voiceAutoSend)
        put("voiceAutoRead", voiceAutoRead)
        put("ttsEngine", ttsEngine)
        put("ttsVoice", ttsVoice)
        put("ttsRate", ttsRate.toDouble())
        put("ttsPitch", ttsPitch.toDouble())
        put("elevenVoiceId", elevenVoiceId)
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
                        muted = m.optBoolean("muted", false),
                        hidden = m.optBoolean("hidden", false),
                        avatarVersion = m.optInt("avatarVersion", 0),
                        avatarEmoji = m.str("avatarEmoji"),
                        pinnedCommands = m.optJSONArray("pinnedCommands")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
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
                notifyBots = o.str("notifyBots") ?: "alerts",
                notifyBackups = o.optBoolean("notifyBackups", true),
                backgroundService = o.optBoolean("backgroundService", false),
                theme = o.str("theme") ?: "dark",
                confirmDownloadMb = o.optInt("confirmDownloadMb", 100),
                indexRetentionDays = o.optInt("indexRetentionDays", 90),
                voiceLang = o.str("voiceLang") ?: "auto",
                voiceAutoSend = o.optBoolean("voiceAutoSend", false),
                voiceAutoRead = o.optBoolean("voiceAutoRead", false),
                ttsEngine = o.str("ttsEngine") ?: "system",
                ttsVoice = o.str("ttsVoice") ?: "",
                ttsRate = o.optDouble("ttsRate", 1.0).toFloat(),
                ttsPitch = o.optDouble("ttsPitch", 1.0).toFloat(),
                elevenVoiceId = o.str("elevenVoiceId") ?: "",
            )
        }
    }
}

/** Configuration locale (catégories, favoris, canaux de sauvegarde, préférences), stockée en JSON. */
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
