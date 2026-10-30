package com.botdesk.app

import android.app.Application

class BotDeskApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.tg.start()
    }
}
