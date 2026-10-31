package com.botdesk.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.botdesk.app.ui.BotDeskTheme
import com.botdesk.app.ui.LocalApp
import com.botdesk.app.ui.Root

class MainActivity : FragmentActivity() {
    private val container get() = (application as BotDeskApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            val cfg by container.store.config.collectAsState()
            BotDeskTheme(cfg.theme) {
                CompositionLocalProvider(LocalApp provides container) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Root(this@MainActivity)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val id = i?.getLongExtra("chat_id", 0L) ?: 0L
        if (id != 0L) container.pendingChat.value = id
    }

    override fun onStart() {
        super.onStart()
        AppState.foreground = true
        container.notifier.syncService()
    }

    override fun onStop() {
        super.onStop()
        AppState.foreground = false
        if (!isChangingConfigurations) container.lock.lockNow()
    }
}
