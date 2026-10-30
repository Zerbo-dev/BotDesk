package com.botdesk.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.botdesk.app.ui.BotDeskTheme
import com.botdesk.app.ui.LocalApp
import com.botdesk.app.ui.Root

class MainActivity : FragmentActivity() {
    private val container get() = (application as BotDeskApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BotDeskTheme {
                CompositionLocalProvider(LocalApp provides container) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Root(this@MainActivity)
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) container.lock.lockNow()
    }
}
