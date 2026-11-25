package com.botdesk.app

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.botdesk.app.ui.AudioController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Service de premier plan, lancé seulement quand il est utile :
 * - pendant la lecture d'un audio (notification avec titre, pause/lecture et fermer) ;
 * - ou si l'utilisateur a activé « rester connecté en arrière-plan » (notification discrète, importance minimale).
 * Il s'arrête tout seul dès que ni l'un ni l'autre n'est vrai.
 */
class TdService : Service() {
    companion object {
        const val ACTION_TOGGLE = "com.botdesk.app.TOGGLE_AUDIO"
        const val ACTION_STOP = "com.botdesk.app.STOP_AUDIO"
        const val NOTIF_ID = 1
    }

    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun goForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val c = (application as BotDeskApp).container
        when (intent?.action) {
            ACTION_TOGGLE -> AudioController.toggle()
            ACTION_STOP -> AudioController.stop()
        }
        goForeground(c.notifier.serviceNotification())
        if (job == null) {
            job = c.scope.launch {
                combine(AudioController.now, AudioController.state, c.store.config) { n, s, cfg ->
                    Triple(n?.file?.id ?: 0, s.second, cfg.backgroundService)
                }.collect { (trackId, _, background) ->
                    if (trackId == 0 && !background) {
                        stopSelf()
                    } else {
                        val n = c.notifier.serviceNotification()
                        withContext(Dispatchers.Main) { goForeground(n) }
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        job = null
        super.onDestroy()
    }
}
