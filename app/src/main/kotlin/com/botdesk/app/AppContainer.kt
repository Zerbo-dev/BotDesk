package com.botdesk.app

import android.app.Application
import com.botdesk.app.core.LockManager
import com.botdesk.app.core.SecureStore
import com.botdesk.app.data.FileIndex
import com.botdesk.app.data.LocalStore
import com.botdesk.app.td.Telegram
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
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

    /** Conversation à ouvrir (clic sur une notification). */
    val pendingChat = MutableStateFlow<Long?>(null)

    init {
        scope.launch {
            tg.msgEvents.collect { e ->
                if (e is TdApi.UpdateNewMessage) notifier.onMessage(e.message)
            }
        }
        index.applyRetention()
    }
}
