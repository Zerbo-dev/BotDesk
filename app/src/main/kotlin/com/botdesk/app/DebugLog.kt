package com.botdesk.app

import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Petit journal en mémoire (80 lignes) pour diagnostiquer notifications et boutons ; jamais envoyé nulle part. */
object DebugLog {
    private val lines = ArrayDeque<String>()
    val tail = MutableStateFlow<List<String>>(emptyList())

    @Synchronized
    fun add(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        lines.addFirst("$t $msg")
        while (lines.size > 80) lines.removeLast()
        tail.value = lines.toList()
    }
}
