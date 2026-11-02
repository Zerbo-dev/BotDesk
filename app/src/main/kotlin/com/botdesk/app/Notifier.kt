package com.botdesk.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
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
        if (m.isOutgoing) return
        val cfg = store.config.value
        val chat = tg.chat(m.chatId)
        if (chat == null) { DebugLog.add("Message ${m.chatId} : conversation inconnue, pas de notification"); return }
        val isBot = tg.isBotChat(chat)
        val isBackup = m.chatId in cfg.backupChannels
        if (!isBot && !isBackup) return
        if (AppState.foreground) { DebugLog.add("${chat.title} : app au premier plan, pas de notification"); return }
        val text = Msg.text(m)
        val body = if (lock.isPinSet) "Nouveau message" else text.take(160)

        if (isBot) {
            val meta = cfg.bots[m.chatId]
            if (meta?.muted == true || meta?.hidden == true) { DebugLog.add("${chat.title} : bot en sourdine ou masqué"); return }
            val alert = containsAny(text, cfg.alertKeywords)
            when (cfg.notifyBots) {
                "off" -> { DebugLog.add("${chat.title} : notifications des bots désactivées"); return }
                "alerts" -> if (!alert) { DebugLog.add("${chat.title} : mode « alertes seulement », aucun mot-clé trouvé"); return }
            }
            post(chat, chat.title, body, alert)
        } else {
            val doc = Msg.document(m)
            if (doc == null) { DebugLog.add("${chat.title} : message sans fichier dans un dépôt, ignoré"); return }
            if (!cfg.notifyBackups) { DebugLog.add("${chat.title} : notifications de sauvegardes désactivées"); return }
            post(chat, chat.title, if (lock.isPinSet) "Nouvelle sauvegarde" else "Nouveau fichier : " + doc.fileName, false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun post(chat: TdApi.Chat, title: String, body: String, alert: Boolean) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) { DebugLog.add("$title : notification BLOQUÉE (autorisation Android refusée)"); return }
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
        try {
            nm.notify(chat.id.hashCode(), n)
            DebugLog.add("$title : notification affichée")
        } catch (e: SecurityException) {
            DebugLog.add("$title : notification refusée par Android (${e.message})")
        }
    }

    /** Envoie une notification de test ; retourne un message lisible. */
    @SuppressLint("MissingPermission")
    fun postTest(): String {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return "Android bloque les notifications de BotDesk. Autorisez-les dans les réglages de l'app."
        val n = NotificationCompat.Builder(ctx, CH_MSG)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("BotDesk")
            .setContentText("Notification de test : l'affichage fonctionne.")
            .setAutoCancel(true)
            .build()
        return try {
            nm.notify(9999, n)
            DebugLog.add("Notification de test envoyée")
            "Notification de test envoyée. Si vous ne la voyez pas, vérifiez le mode « Ne pas déranger » et les réglages de la chaîne « Messages des bots »."
        } catch (e: SecurityException) {
            "Autorisation refusée : " + e.message
        }
    }

    fun status(): List<String> {
        val cfg = store.config.value
        val enabled = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        val pm = ctx.getSystemService(PowerManager::class.java)
        val battery = try { pm.isIgnoringBatteryOptimizations(ctx.packageName) } catch (e: Exception) { false }
        return listOf(
            "Notifications autorisées par Android : " + if (enabled) "oui" else "NON",
            "Mode des bots : " + when (cfg.notifyBots) { "off" -> "aucune"; "alerts" -> "alertes seulement"; else -> "tous les messages" },
            "Connexion en arrière-plan : " + if (cfg.backgroundService) "activée" else "DÉSACTIVÉE",
            "Optimisation de batterie ignorée : " + if (battery) "oui" else "non (Android peut arrêter l'app)",
            "Connexion Telegram : " + tg.auth.value,
        )
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
            try { ContextCompat.startForegroundService(ctx, i); DebugLog.add("Service d'arrière-plan démarré") } catch (e: Exception) { DebugLog.add("Service refusé : ${e.message}") }
        } else {
            ctx.stopService(i)
        }
    }
}
