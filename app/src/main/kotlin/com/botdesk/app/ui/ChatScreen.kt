@file:OptIn(ExperimentalMaterial3Api::class)

package com.botdesk.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.botdesk.app.td.Msg
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

@Composable
fun ChatScreen(chatId: Long, onBack: () -> Unit) {
    val app = LocalApp.current
    val tg = app.tg
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg by app.store.config.collectAsState()
    val chat = tg.chat(chatId)
    val save = rememberFileSaver()

    var msgs by remember(chatId) { mutableStateOf(listOf<TdApi.Message>()) }
    var loading by remember(chatId) { mutableStateOf(false) }
    var endReached by remember(chatId) { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var commands by remember(chatId) { mutableStateOf<List<Pair<String, String>>?>(null) }
    var menu by remember { mutableStateOf(false) }
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

    LaunchedEffect(chatId) { loadMore() }

    LaunchedEffect(chatId) {
        tg.msgEvents.collect { e ->
            when (e) {
                is TdApi.UpdateNewMessage ->
                    if (e.message.chatId == chatId) msgs = listOf(e.message) + msgs.filter { it.id != e.message.id }
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

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Column { Text(cfg.bots[chatId]?.displayName ?: chat?.title ?: "Chat"); Text(activityLabel(chat?.lastMessage?.date ?: 0), style = MaterialTheme.typography.bodySmall) } },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
            actions = {
                IconButton(onClick = {
                    menu = true
                    if (commands == null && chat != null) {
                        val uid = tg.botUserId(chat)
                        if (uid != null) scope.launch { commands = try { tg.botCommands(uid) } catch (e: Exception) { emptyList() } }
                    }
                }) { Icon(Icons.Default.MoreVert, "Commandes") }
                DropdownMenu(menu, { menu = false }) {
                    val cmds = commands
                    if (cmds == null) DropdownMenuItem(text = { Text("Chargement…") }, onClick = {})
                    else if (cmds.isEmpty()) DropdownMenuItem(text = { Text("Aucune commande déclarée") }, onClick = {})
                    else cmds.forEach { (c, d) ->
                        DropdownMenuItem(text = { Column { Text("/$c"); Text(d, style = MaterialTheme.typography.bodySmall) } }, onClick = { menu = false; trySend("/$c") })
                    }
                }
            },
        )

        ErrorText(error)
        LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp), state = listState, reverseLayout = true) {
            items(msgs, key = { it.id }) { m -> Bubble(m, save) { b -> onInline(m, b) } }
            if (loading) item { Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp)) } }
        }

        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, placeholder = { Text("Écrire un message…") }, modifier = Modifier.weight(1f), maxLines = 4)
            IconButton(onClick = { trySend(input); input = "" }, enabled = input.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, "Envoyer", tint = Accent)
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
private fun Bubble(m: TdApi.Message, onSave: (TdApi.Document) -> Unit, onInline: (TdApi.InlineKeyboardButton) -> Unit) {
    val out = m.isOutgoing
    val failed = m.sendingState is TdApi.MessageSendingStateFailed
    val pending = m.sendingState is TdApi.MessageSendingStatePending
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalAlignment = if (out) Alignment.End else Alignment.Start) {
        Column(
            Modifier.widthIn(max = 300.dp)
                .background(if (out) Accent else MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                .padding(10.dp)
        ) {
            Text(Msg.text(m))
            Msg.document(m)?.let { d ->
                TextButton(onClick = { onSave(d) }) { Text("Enregistrer · " + fmtSize(d.document.size)) }
            }
            Text(
                fmtDate(m.date) + if (failed) " · ⚠ échec" else if (pending) " · ⏳" else "",
                style = MaterialTheme.typography.labelSmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
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
