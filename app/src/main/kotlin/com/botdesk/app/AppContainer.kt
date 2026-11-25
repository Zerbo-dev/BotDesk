package com.botdesk.app

import android.app.Application
import com.botdesk.app.core.LockManager
import com.botdesk.app.core.SecureStore
import com.botdesk.app.data.FileIndex
import com.botdesk.app.data.LocalStore
import com.botdesk.app.td.AuthUi
import com.botdesk.app.td.Telegram
import com.botdesk.app.ui.GalleryRequest
import com.botdesk.app.ui.ViewerRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val secure = SecureStore(app)
    val lock = LockManager(secure, app)
    val store = LocalStore(app)
    val tg = Telegram(app, secure, scope)
    val index = FileIndex(app) { store.config.value.indexRetentionDays }
    val notifier = Notifier(app, tg, store, lock)
    val downloads = DownloadManager(this)

    /** Conversation à ouvrir (clic sur une notification). */
    val pendingChat = MutableStateFlow<Long?>(null)

    /** Fichier ouvert dans un lecteur interne (null = aucun). */
    val viewer = MutableStateFlow<ViewerRequest?>(null)

    /** Galerie photos/vidéos ouverte (balayage gauche/droite). */
    val gallery = MutableStateFlow<GalleryRequest?>(null)

    init {
        com.botdesk.app.ui.Speaker.bind(store, secure)
        com.botdesk.app.ui.AudioController.bind(this)
        scope.launch {
            tg.msgEvents.collect { e ->
                if (e is TdApi.UpdateNewMessage) notifier.onMessage(e.message)
            }
        }
        index.applyRetention()
        // Nettoyage automatique du cache (limite de taille et ancienneté), toutes les 6 h
        scope.launch {
            while (true) {
                delay(60_000L)
                val c = store.config.value
                if (tg.auth.value is AuthUi.Ready) {
                    try {
                        tg.optimize(
                            if (c.cacheLimitMb > 0) c.cacheLimitMb * 1_000_000L else -1L,
                            if (c.cacheTtlDays > 0) c.cacheTtlDays * 86_400 else -1,
                        )
                    } catch (e: Exception) { }
                }
                delay(6 * 3_600_000L)
            }
        }
    }
}
