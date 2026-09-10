package com.paradisemc.rokid.plugin.voicerelay

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class VoiceRelayNotificationListener : NotificationListenerService() {

    private var lastFingerprint: String? = null
    private var lastFingerprintAt: Long = 0L

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (sbn.isOngoing) return

        val app = when (sbn.packageName) {
            "com.whatsapp" -> "WhatsApp"
            "com.whatsapp.w4b" -> "WhatsApp Business"
            "org.telegram.messenger" -> "Telegram"
            "org.thunderdog.challegram" -> "Telegram X"
            else -> return
        }

        val extras = sbn.notification.extras ?: return
        val sender = (
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)
                ?: app
            ).toString().trim()

        val text = extractLatestMessage(extras)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: return

        if (text.isBlank()) return

        val now = System.currentTimeMillis()
        val fingerprint = "$app\u0000$sender\u0000$text"
        if (fingerprint == lastFingerprint && now - lastFingerprintAt < 2500L) return
        lastFingerprint = fingerprint
        lastFingerprintAt = now

        VoiceRelayPluginService.deliverIncoming(
            applicationContext,
            IncomingMessage(app = app, sender = sender, text = text),
        )
    }

    private fun extractLatestMessage(extras: android.os.Bundle): String? {
        val bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles)
        return messages.lastOrNull()?.text?.toString()?.takeIf { it.isNotBlank() }
    }
}
