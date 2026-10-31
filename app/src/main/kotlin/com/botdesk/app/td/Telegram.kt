package com.botdesk.app.td

import android.content.Context
import android.os.Build
import android.util.Base64
import com.botdesk.app.core.SecureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TdException(val code: Int, message: String) : Exception(message)

sealed interface AuthUi {
    data object Starting : AuthUi
    data object NeedCredentials : AuthUi
    data object WaitPhone : AuthUi
    data object WaitCode : AuthUi
    data class WaitPassword(val hint: String) : AuthUi
    data object Ready : AuthUi
    data class Unsupported(val state: String) : AuthUi
}

data class BackupFile(
    val msgId: Long,
    val chatId: Long,
    val date: Int,
    val doc: TdApi.Document,
    val caption: String,
    val sender: String = "",
)

/** Seule classe qui parle à TDLib. Le reste de l'app ne voit que des flux et des fonctions suspend. */
class Telegram(
    private val ctx: Context,
    private val secure: SecureStore,
    private val scope: CoroutineScope,
) {
    private var client: Client? = null
    val auth = MutableStateFlow<AuthUi>(AuthUi.Starting)

    /** Incrémenté à chaque changement de chats/utilisateurs (déclenche la recomposition). */
    val version = MutableStateFlow(0L)

    private val _events = MutableSharedFlow<TdApi.Object>(extraBufferCapacity = 256)
    val msgEvents: SharedFlow<TdApi.Object> = _events.asSharedFlow()

    val myName = MutableStateFlow<String?>(null)

    private val missingUsers = ConcurrentHashMap.newKeySet<Long>()
    private val chats = ConcurrentHashMap<Long, TdApi.Chat>()
    private val users = ConcurrentHashMap<Long, TdApi.User>()

    private fun bump() { version.update { it + 1 } }

    fun start() {
        if (client != null) return
        Client.execute(TdApi.SetLogVerbosityLevel(1))
        client = Client.create({ o -> handle(o) }, null, null)
    }

    // ---------- requêtes ----------

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : TdApi.Object> request(f: TdApi.Function<T>): T = suspendCancellableCoroutine { cont ->
        val c = client
        if (c == null) {
            cont.resumeWithException(IllegalStateException("TDLib n'est pas démarré"))
        } else {
            c.send(f, { r ->
                if (r is TdApi.Error) cont.resumeWithException(TdException(r.code, r.message))
                else cont.resume(r as T)
            }, { e -> cont.resumeWithException(e) })
        }
    }

    // ---------- mises à jour ----------

    private fun handle(o: TdApi.Object) {
        when (o) {
            is TdApi.UpdateAuthorizationState -> onAuth(o.authorizationState)
            is TdApi.UpdateNewChat -> { chats[o.chat.id] = o.chat; bump() }
            is TdApi.UpdateChatLastMessage -> { chats[o.chatId]?.lastMessage = o.lastMessage; bump() }
            is TdApi.UpdateChatReadInbox -> { chats[o.chatId]?.unreadCount = o.unreadCount; bump() }
            is TdApi.UpdateChatTitle -> { chats[o.chatId]?.title = o.title; bump() }
            is TdApi.UpdateUser -> { users[o.user.id] = o.user; bump() }
            is TdApi.UpdateNewMessage,
            is TdApi.UpdateMessageContent,
            is TdApi.UpdateMessageSendSucceeded,
            is TdApi.UpdateMessageSendFailed -> _events.tryEmit(o)
            else -> Unit
        }
    }

    private fun onAuth(s: TdApi.AuthorizationState) {
        when (s) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                val id = secure.getString("api_id")?.toIntOrNull()
                val hash = secure.getString("api_hash")
                if (id == null || hash.isNullOrBlank()) auth.value = AuthUi.NeedCredentials
                else sendParameters(id, hash)
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> auth.value = AuthUi.WaitPhone
            is TdApi.AuthorizationStateWaitCode -> auth.value = AuthUi.WaitCode
            is TdApi.AuthorizationStateWaitPassword -> auth.value = AuthUi.WaitPassword(s.passwordHint ?: "")
            is TdApi.AuthorizationStateReady -> {
                auth.value = AuthUi.Ready
                scope.launch { try { myName.value = request(TdApi.GetMe()).firstName } catch (e: Exception) { } }
                scope.launch { try { loadChats() } catch (e: Exception) { /* réessayé via le bouton Actualiser */ } }
            }
            is TdApi.AuthorizationStateLoggingOut, is TdApi.AuthorizationStateClosing -> auth.value = AuthUi.Starting
            is TdApi.AuthorizationStateClosed -> {
                client = null
                chats.clear(); users.clear(); missingUsers.clear(); myName.value = null; bump()
                auth.value = AuthUi.Starting
                scope.launch { start() }
            }
            else -> auth.value = AuthUi.Unsupported(s.javaClass.simpleName)
        }
    }

    private fun databaseKey(): ByteArray {
        secure.getString("db_key")?.let { return Base64.decode(it, Base64.NO_WRAP) }
        val k = ByteArray(32).also { SecureRandom().nextBytes(it) }
        secure.putString("db_key", Base64.encodeToString(k, Base64.NO_WRAP))
        return k
    }

    private fun sendParameters(apiId: Int, apiHash: String) {
        val p = TdApi.SetTdlibParameters()
        p.databaseDirectory = File(ctx.filesDir, "tdlib").absolutePath
        p.filesDirectory = File(ctx.filesDir, "tdfiles").absolutePath
        p.databaseEncryptionKey = databaseKey()
        p.useFileDatabase = true
        p.useChatInfoDatabase = true
        p.useMessageDatabase = true
        p.useSecretChats = false
        p.apiId = apiId
        p.apiHash = apiHash
        p.systemLanguageCode = Locale.getDefault().language.ifBlank { "fr" }
        p.deviceModel = Build.MODEL
        p.systemVersion = Build.VERSION.RELEASE
        p.applicationVersion = "0.1.0"
        scope.launch {
            try { request(p) } catch (e: Exception) {
                auth.value = AuthUi.Unsupported("Paramètres TDLib refusés : " + e.message)
            }
        }
    }

    fun submitCredentials(apiId: Int, apiHash: String) {
        secure.putString("api_id", apiId.toString())
        secure.putString("api_hash", apiHash)
        sendParameters(apiId, apiHash)
    }

    // ---------- authentification ----------

    suspend fun submitPhone(phone: String) { request(TdApi.SetAuthenticationPhoneNumber(phone, null)) }
    suspend fun submitCode(code: String) { request(TdApi.CheckAuthenticationCode(code)) }
    suspend fun submitPassword(password: String) { request(TdApi.CheckAuthenticationPassword(password)) }
    suspend fun logout() { request(TdApi.LogOut()) }

    // ---------- chats ----------

    suspend fun loadChats() {
        try {
            for (i in 0 until 50) request(TdApi.LoadChats(TdApi.ChatListMain(), 100))
        } catch (e: TdException) {
            if (e.code != 404) throw e
        }
        bump()
    }

    fun chat(id: Long): TdApi.Chat? = chats[id]
    fun user(id: Long): TdApi.User? = users[id]

    fun allChats(): List<TdApi.Chat> = chats.values.sortedByDescending { it.lastMessage?.date ?: 0 }

    fun isBotChat(c: TdApi.Chat): Boolean {
        val t = c.type
        return t is TdApi.ChatTypePrivate && users[t.userId]?.type is TdApi.UserTypeBot
    }

    fun botUserId(c: TdApi.Chat): Long? = (c.type as? TdApi.ChatTypePrivate)?.userId

    fun bots(): List<TdApi.Chat> = allChats().filter { isBotChat(it) }

    fun channels(): List<TdApi.Chat> = allChats().filter {
        val t = it.type
        t is TdApi.ChatTypeSupergroup || t is TdApi.ChatTypeBasicGroup
    }

    fun channelKind(c: TdApi.Chat): String {
        val t = c.type
        return if (t is TdApi.ChatTypeSupergroup && t.isChannel) "Canal" else "Groupe"
    }

    // ---------- messages ----------

    suspend fun history(chatId: Long, fromId: Long, limit: Int = 50): List<TdApi.Message> {
        for (attempt in 0 until 3) {
            val r = request(TdApi.GetChatHistory(chatId, fromId, 0, limit, false))
            val list = r.messages?.filterNotNull() ?: emptyList()
            if (list.isNotEmpty()) return list
            if (attempt < 2) delay(500)
        }
        return emptyList()
    }

    suspend fun sendText(chatId: Long, text: String): TdApi.Message {
        val content = TdApi.InputMessageText()
        content.text = TdApi.FormattedText(text, arrayOf<TdApi.TextEntity>())
        val req = TdApi.SendMessage()
        req.chatId = chatId
        req.inputMessageContent = content
        return request(req)
    }

    suspend fun botCommands(botUserId: Long): List<Pair<String, String>> {
        val full = request(TdApi.GetUserFullInfo(botUserId))
        val info = full.botInfo ?: return emptyList()
        return info.commands?.filterNotNull()?.map { it.command to it.description } ?: emptyList()
    }

    /** Appuie sur un bouton inline "callback". Retourne le texte de réponse éventuel. */
    suspend fun pressInline(chatId: Long, messageId: Long, data: ByteArray): String? {
        val ans = request(TdApi.GetCallbackQueryAnswer(chatId, messageId, TdApi.CallbackQueryPayloadData(data)))
        return ans.text?.takeIf { it.isNotBlank() }
    }

    // ---------- fichiers / sauvegardes ----------

    suspend fun download(fileId: Int): TdApi.File {
        val req = TdApi.DownloadFile()
        req.fileId = fileId
        req.priority = 16
        req.offset = 0L
        req.limit = 0L
        req.synchronous = true
        return request(req)
    }

    private suspend fun userName(id: Long): String {
        if (id == 0L || id in missingUsers) return ""
        val u = users[id] ?: try {
            request(TdApi.GetUser(id)).also { users[id] = it }
        } catch (e: Exception) {
            missingUsers.add(id)
            null
        }
        return u?.let { (it.firstName + " " + it.lastName).trim() } ?: ""
    }

    private suspend fun senderOf(m: TdApi.Message): String {
        if (m.viaBotUserId != 0L) return userName(m.viaBotUserId)
        val s = m.senderId
        if (s is TdApi.MessageSenderUser) return userName(s.userId)
        return m.authorSignature ?: ""
    }

    suspend fun backupFiles(chatId: Long, maxPages: Int = 5): List<BackupFile> {
        val out = mutableListOf<BackupFile>()
        var from = 0L
        for (i in 0 until maxPages) {
            val page = history(chatId, from, 100)
            if (page.isEmpty()) break
            for (m in page) {
                val d = m.content as? TdApi.MessageDocument ?: continue
                out += BackupFile(m.id, chatId, m.date, d.document, d.caption.text, senderOf(m))
            }
            from = page.last().id
        }
        return out
    }

    // ---------- recherche et stockage ----------

    suspend fun searchInChat(chatId: Long, query: String, limit: Int = 20): List<TdApi.Message> {
        val r = TdApi.SearchChatMessages()
        r.chatId = chatId
        r.query = query
        r.fromMessageId = 0L
        r.offset = 0
        r.limit = limit
        val res = request(r)
        return res.messages?.filterNotNull() ?: emptyList()
    }

    suspend fun storageBytes(): Long = request(TdApi.GetStorageStatisticsFast()).filesSize

    suspend fun clearDownloads() {
        val r = TdApi.OptimizeStorage()
        r.size = 0L
        r.ttl = -1
        r.count = -1
        r.immunityDelay = 0
        r.fileTypes = arrayOf<TdApi.FileType>()
        r.chatIds = LongArray(0)
        r.excludeChatIds = LongArray(0)
        request(r)
    }
}
