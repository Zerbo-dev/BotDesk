@file:OptIn(ExperimentalMaterial3Api::class)

package com.botdesk.app.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.util.Locale
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.botdesk.app.data.BotMeta
import com.botdesk.app.td.BackupFile
import com.botdesk.app.td.Msg
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

@Composable
fun ChatScreen(chatId: Long, onBack: () -> Unit, readOnly: Boolean = false, onFiles: (() -> Unit)? = null) {
    val app = LocalApp.current
    val tg = app.tg
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg by app.store.config.collectAsState()
    val chat = tg.chat(chatId)
    val save = rememberFileSaver()
    val speakingId by Speaker.speakingId.collectAsState()

    var msgs by remember(chatId) { mutableStateOf(listOf<TdApi.Message>()) }
    var loading by remember(chatId) { mutableStateOf(false) }
    var endReached by remember(chatId) { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var commands by remember(chatId) { mutableStateOf<List<Pair<String, String>>?>(null) }
    var showCommands by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun loadMore() {
        if (loading || endReached) return
        loading = true
        scope.launch {
            try {
                val page = tg.history(chatId, msgs.lastOrNull()?.id ?: 0L, 50)
                val known = msgs.map { it.id }.toSet()
                val fresh = page.filter { it.id !in known }
                if (fresh.isEmpty()) endReached = true else msgs = msgs + fresh
            } catch (e: Exception) {
                error = e.message
            }
            loading = false
        }
    }

    LaunchedEffect(chatId) {
        try { tg.openChat(chatId) } catch (e: Exception) { /* lecture possible sans */ }
        loadMore()
    }
    DisposableEffect(chatId) {
        onDispose { tg.closeChat(chatId); AudioController.stop(); Speaker.stop() }
    }
    LaunchedEffect(chatId) {
        val uid = tg.chat(chatId)?.let { tg.botUserId(it) }
        commands = if (uid == null) emptyList() else try { tg.botCommands(uid) } catch (e: Exception) { emptyList() }
    }

    LaunchedEffect(chatId) {
        tg.msgEvents.collect { e ->
            when (e) {
                is TdApi.UpdateNewMessage ->
                    if (e.message.chatId == chatId) {
                        msgs = listOf(e.message) + msgs.filter { it.id != e.message.id }
                        val vc = app.store.config.value
                        if (vc.voiceAutoRead && !e.message.isOutgoing && e.message.content is TdApi.MessageText) {
                            Speaker.speak(ctx, Msg.text(e.message), e.message.id, vc.voiceLang)
                        }
                    }
                is TdApi.UpdateMessageContent ->
                    if (e.chatId == chatId) {
                        msgs.firstOrNull { it.id == e.messageId }?.content = e.newContent
                        msgs = msgs.toList()
                    }
                is TdApi.UpdateMessageSendSucceeded ->
                    if (e.message.chatId == chatId) msgs = msgs.map { if (it.id == e.oldMessageId) e.message else it }
                is TdApi.UpdateMessageSendFailed ->
                    if (e.message.chatId == chatId) msgs = msgs.map { if (it.id == e.oldMessageId) e.message else it }
                else -> Unit
            }
        }
    }

    val atEnd by remember {
        derivedStateOf {
            val l = listState.layoutInfo
            l.totalItemsCount > 0 && (l.visibleItemsInfo.lastOrNull()?.index ?: 0) >= l.totalItemsCount - 3
        }
    }
    LaunchedEffect(atEnd, msgs.size) { if (atEnd) loadMore() }

    fun doSend(text: String) {
        scope.launch {
            try {
                val m = tg.sendText(chatId, text)
                if (msgs.none { it.id == m.id }) msgs = listOf(m) + msgs
            } catch (e: Exception) {
                error = "Envoi impossible : " + e.message
            }
        }
    }

    fun trySend(text: String) {
        if (text.isBlank()) return
        if (containsAny(text, cfg.sensitiveKeywords)) confirm = text to { doSend(text) } else doSend(text)
    }

    val dictation = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!said.isNullOrBlank()) {
                if (app.store.config.value.voiceAutoSend) trySend(said) else input = (input + " " + said).trim()
            }
        }
    }

    fun startDictation() {
        val lang = when (app.store.config.value.voiceLang) {
            "fr" -> "fr-FR"
            "en" -> "en-US"
            else -> Locale.getDefault().toLanguageTag()
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Parlez…")
        app.lock.skipNextLock = true
        try {
            dictation.launch(i)
        } catch (e: Exception) {
            app.lock.skipNextLock = false
            toast(ctx, "Reconnaissance vocale indisponible sur ce téléphone")
        }
    }

    fun onInline(m: TdApi.Message, b: TdApi.InlineKeyboardButton) {
        val run = {
            when (val t = b.type) {
                is TdApi.InlineKeyboardButtonTypeCallback -> scope.launch {
                    try {
                        tg.pressInline(chatId, m.id, t.data)?.let { toast(ctx, it) }
                    } catch (e: Exception) {
                        toast(ctx, "Action sans réponse : " + e.message)
                    }
                }
                is TdApi.InlineKeyboardButtonTypeUrl ->
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(t.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                else -> toast(ctx, "Ce type de bouton n'est pas pris en charge")
            }
            Unit
        }
        if (containsAny(b.text, cfg.sensitiveKeywords)) confirm = ("Bouton : " + b.text) to run else run()
    }

    fun togglePin(cmd: String) {
        val meta = app.store.config.value.bots[chatId] ?: BotMeta()
        val pins = if (cmd in meta.pinnedCommands) meta.pinnedCommands - cmd else meta.pinnedCommands + cmd
        app.store.update { it.copy(bots = it.bots + (chatId to meta.copy(pinnedCommands = pins))) }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Column { Text(cfg.bots[chatId]?.displayName ?: chat?.title ?: "Chat"); Text(activityLabel(chat?.lastMessage?.date ?: 0), style = MaterialTheme.typography.bodySmall) } },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
            actions = { onFiles?.let { f -> TextButton(onClick = f) { Text("Fichiers") } } },
        )

        ErrorText(error)
        LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp), state = listState, reverseLayout = true) {
            items(msgs, key = { it.id }) { m -> Bubble(
                m,
                speaking = speakingId == m.id,
                onSpeak = {
                    if (speakingId == m.id) Speaker.stop()
                    else Speaker.speak(ctx, Msg.caption(m) ?: Msg.text(m), m.id, cfg.voiceLang)
                },
                onSave = { d -> save(BackupFile(m.id, chatId, m.date, d, "", "")) },
                onInline = { b -> onInline(m, b) },
            ) } 
            if (loading) item { Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp)) } }
        }

        if (!readOnly) {
        val kb = msgs.firstOrNull { !it.isOutgoing && it.replyMarkup != null }?.replyMarkup as? TdApi.ReplyMarkupShowKeyboard
        kb?.rows?.forEach { row ->
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { b -> OutlinedButton(onClick = { trySend(b.text) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(b.text, maxLines = 1) } }
            }
        }
        val pins = cfg.bots[chatId]?.pinnedCommands ?: emptyList()
        val typed = input.trim()
        val suggestions = if (typed.startsWith("/") && !typed.contains(" ")) {
            (commands ?: emptyList()).map { it.first }.filter { ("/" + it).startsWith(typed, true) && "/$it" != typed }
        } else emptyList()
        if (suggestions.isNotEmpty() || (input.isBlank() && pins.isNotEmpty())) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (suggestions.isNotEmpty()) {
                    suggestions.forEach { c -> SuggestionChip(onClick = { input = "/$c " }, label = { Text("/$c") }) }
                } else {
                    pins.forEach { c ->
                        AssistChip(onClick = { trySend("/$c") }, label = { Text((if (containsAny(c, cfg.sensitiveKeywords)) "⚠ " else "") + "/$c") })
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { showCommands = true }) { Text("/", style = MaterialTheme.typography.titleLarge, color = Accent) }
            OutlinedTextField(input, { input = it }, placeholder = { Text("Message ou /commande") }, modifier = Modifier.weight(1f), maxLines = 4)
            IconButton(onClick = { startDictation() }) { Text("🎤", style = MaterialTheme.typography.titleLarge) }
            IconButton(onClick = { trySend(input); input = "" }, enabled = input.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, "Envoyer", tint = Accent)
            }
        }
        }
    }

    if (showCommands) {
        var filter by remember { mutableStateOf("") }
        val cmdList = commands
        val pinned = cfg.bots[chatId]?.pinnedCommands ?: emptyList()
        ModalBottomSheet(onDismissRequest = { showCommands = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text("Commandes", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Toucher une commande l'insère dans le message (pour ajouter des arguments). ➤ l'envoie tout de suite. ♥ l'épingle en raccourci.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                OutlinedTextField(filter, { filter = it }, label = { Text("Filtrer") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val shown = (cmdList ?: emptyList())
                    .filter { filter.isBlank() || it.first.contains(filter, true) || it.second.contains(filter, true) }
                    .sortedByDescending { it.first in pinned }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    if (cmdList == null) item { Text("Chargement…", modifier = Modifier.padding(top = 12.dp)) }
                    else if (shown.isEmpty()) item {
                        Text(
                            if (cmdList.isEmpty()) "Ce bot ne déclare aucune commande. Tapez-la directement dans le message." else "Aucun résultat.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    items(shown, key = { it.first }) { (c, d) ->
                        val sensitive = containsAny(c, cfg.sensitiveKeywords)
                        Row(
                            Modifier.fillMaxWidth().clickable { input = "/$c "; showCommands = false }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("/$c" + if (sensitive) "  ⚠" else "", style = MaterialTheme.typography.titleSmall)
                                if (d.isNotBlank()) Text(d, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { togglePin(c) }) {
                                Icon(if (c in pinned) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Épingler", tint = if (c in pinned) Accent else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { showCommands = false; trySend("/$c") }) {
                                Icon(Icons.AutoMirrored.Filled.Send, "Envoyer", tint = Accent)
                            }
                        }
                    }
                }
            }
        }
    }

    confirm?.let { (label, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Confirmer l'action") },
            text = { Text("« $label » ressemble à une action sensible. Voulez-vous vraiment l'envoyer ?") },
            confirmButton = { TextButton(onClick = { confirm = null; action() }) { Text("Envoyer") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun Bubble(
    m: TdApi.Message,
    speaking: Boolean,
    onSpeak: () -> Unit,
    onSave: (TdApi.Document) -> Unit,
    onInline: (TdApi.InlineKeyboardButton) -> Unit,
) {
    val out = m.isOutgoing
    val failed = m.sendingState is TdApi.MessageSendingStateFailed
    val pending = m.sendingState is TdApi.MessageSendingStatePending
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalAlignment = if (out) Alignment.End else Alignment.Start) {
        Column(
            Modifier.widthIn(max = 300.dp)
                .background(if (out) Accent else MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                .padding(10.dp)
        ) {
            when (val c = m.content) {
                is TdApi.MessagePhoto -> PhotoView(c.photo)
                is TdApi.MessageAudio -> AudioRow(c.audio.audio, c.audio.title.ifBlank { c.audio.fileName }.ifBlank { "Audio" }, c.audio.duration)
                is TdApi.MessageVoiceNote -> AudioRow(c.voiceNote.voice, "Message vocal", c.voiceNote.duration)
                is TdApi.MessageVideo -> VideoRow(c.video)
                is TdApi.MessageDocument -> DocumentRow(c.document) { onSave(c.document) }
                else -> Unit
            }
            val body = Msg.caption(m) ?: Msg.text(m)
            if (body.isNotBlank()) Text(body, color = if (out) Color.White else MaterialTheme.colorScheme.onSurface)
            if (!out && body.isNotBlank() && m.content is TdApi.MessageText) {
                TextButton(onClick = onSpeak, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(if (speaking) "⏹ Arrêter" else "🔊 Écouter") }
            }
            Text(
                fmtDate(m.date) + if (failed) " · ⚠ échec" else if (pending) " · ⏳" else "",
                style = MaterialTheme.typography.labelSmall,
                color = if (failed) MaterialTheme.colorScheme.error else if (out) Color(0xFFD8E0FF) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val rm = m.replyMarkup
        if (!out && rm is TdApi.ReplyMarkupInlineKeyboard) {
            rm.rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    row.forEach { b -> OutlinedButton(onClick = { onInline(b) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(b.text, maxLines = 1) } }
                }
            }
        }
    }
}
