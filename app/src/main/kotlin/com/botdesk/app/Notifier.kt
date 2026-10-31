package com.botdesk.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.botdesk.app.core.LockManager
import com.botdesk.app.data.LocalStore
import com.botdesk.app.td.Msg
import com.botdesk.app.td.Telegram
import com.botdesk.app.ui.containsAny
import org.drinkless.tdlib.TdApi

object AppState {
    @Volatile var foreground = false
}

/** Notifications locales (pas de serveur de push) + service de premier plan optionnel. */
class Notifier(
    private val ctx: Context,
    private val tg: Telegram,
    private val store: LocalStore,
    private val lock: LockManager,
) {
    companion object {
        const val CH_MSG = "messages"
        const val CH_ALERT = "alerts"
        const val CH_SERVICE = "service"
    }

    init {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_MSG, "Messages des bots", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Alertes", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Connexion en arrière-plan", NotificationManager.IMPORTANCE_LOW))
    }

    fun onMessage(m: TdApi.Message) {
        if (m.isOutgoing || AppState.foreground) return
        val cfg = store.config.value
        val chat = tg.chat(m.chatId) ?: return
        val text = Msg.text(m)
        val body = if (lock.isPinSet) "Nouveau message" else text.take(160)

        if (tg.isBotChat(chat)) {
            if (cfg.bots[m.chatId]?.muted == true || cfg.bots[m.chatId]?.hidden == true) return
            val alert = containsAny(text, cfg.alertKeywords)
            when (cfg.notifyBots) {
                "off" -> return
                "alerts" -> if (!alert) return
            }
            post(chat, chat.title, body, alert)
        } else if (m.chatId in cfg.backupChannels) {
            val doc = Msg.document(m) ?: return
            if (!cfg.notifyBackups) return
            post(chat, chat.title, if (lock.isPinSet) "Nouvelle sauvegarde" else "Nouveau fichier : " + doc.fileName, false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun post(chat: TdApi.Chat, title: String, body: String, alert: Boolean) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("chat_id", chat.id)
        }
        val pi = PendingIntent.getActivity(ctx, chat.id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, if (alert) CH_ALERT else CH_MSG)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (alert) "⚠ $title" else title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { nm.notify(chat.id.hashCode(), n) } catch (e: SecurityException) { /* permission refusée */ }
    }

    fun serviceNotification(): Notification {
        val intent = Intent(ctx, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val pi = PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("BotDesk")
            .setContentText("Connexion Telegram active")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    /** Démarre ou arrête le service selon le réglage. À appeler quand l'app est au premier plan. */
    fun syncService() {
        val i = Intent(ctx, TdService::class.java)
        if (store.config.value.backgroundService) {
            try { ContextCompat.startForegroundService(ctx, i) } catch (e: Exception) { /* refusé par le système */ }
        } else {
            ctx.stopService(i)
        }
    }
}
