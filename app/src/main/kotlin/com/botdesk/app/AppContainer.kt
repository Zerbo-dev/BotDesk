package com.botdesk.app

import android.app.Application
import com.botdesk.app.core.LockManager
import com.botdesk.app.core.SecureStore
import com.botdesk.app.data.LocalStore
import com.botdesk.app.td.Telegram
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val secure = SecureStore(app)
    val lock = LockManager(secure, app)
    val store = LocalStore(app)
    val tg = Telegram(app, secure, scope)
}
