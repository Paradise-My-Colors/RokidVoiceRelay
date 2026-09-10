package com.paradisemc.rokid.plugin.voicerelay

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.paradisemc.rokid.plugin.voicerelay.telegram.TelegramClientManager
import com.paradisemc.rokid.plugin.voicerelay.telegram.TelegramSecureStore

class VoiceRelayNotificationListener : NotificationListenerService() {

    private val runtime by lazy { VoiceRelayNoticeRuntime(applicationContext) }
    private var lastFingerprint: String? = null
    private var lastFingerprintAt: Long = 0L

    override fun onCreate() {
        super.onCreate()
        current = this
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        current = this
        PendingMessageStore.setListenerState(this, true)
        if (TelegramSecureStore.hasCredentials(this)) {
            TelegramClientManager.get(this).start()
        }
    }

    override fun onListenerDisconnected() {
        PendingMessageStore.setListenerState(this, false)
        runtime.shutdown()
        super.onListenerDisconnected()

        val component = ComponentName(this, VoiceRelayNotificationListener::class.java)
        val manager = getSystemService(NotificationManager::class.java)
        val accessGranted = runCatching {
            manager?.isNotificationListenerAccessGranted(component) == true
        }.getOrDefault(false)
        if (accessGranted) requestRebind(component)
    }

    override fun onDestroy() {
        if (current === this) current = null
        PendingMessageStore.setListenerState(this, false)
        runtime.shutdown()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (sbn.isOngoing) return

        val app = appNameForPackage(sbn.packageName) ?: return
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
        val fingerprint = "${sbn.packageName}\u0000$sender\u0000$text"
        if (fingerprint == lastFingerprint && now - lastFingerprintAt < 2500L) return
        lastFingerprint = fingerprint
        lastFingerprintAt = now

        val message = IncomingMessage(
            app = app,
            packageName = sbn.packageName,
            notificationKey = sbn.key,
            shortcutId = sbn.notification.shortcutId,
            sender = sender,
            text = text,
        )
        PendingMessageStore.setLastCaptured(this, message)
        runtime.show(message)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        if (appNameForPackage(sbn.packageName) == null) return
        PendingMessageStore.removeByNotificationKey(this, sbn.key)
        VoiceRelayPluginService.notifyInboxChanged()
    }

    private fun appNameForPackage(packageName: String): String? = when (packageName) {
        "com.whatsapp" -> "WhatsApp"
        "com.whatsapp.w4b" -> "WhatsApp Business"
        "org.telegram.messenger" -> "Telegram"
        "org.telegram.messenger.web" -> "Telegram"
        "org.telegram.messenger.beta" -> "Telegram Beta"
        "org.thunderdog.challegram" -> "Telegram X"
        else -> null
    }

    private fun extractLatestMessage(extras: android.os.Bundle): String? {
        val bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles)
        return messages.lastOrNull()?.text?.toString()?.takeIf { it.isNotBlank() }
    }

    companion object {
        @Volatile private var current: VoiceRelayNotificationListener? = null

        fun dismissNotification(notificationKey: String?) {
            if (notificationKey.isNullOrBlank()) return
            val listener = current ?: return
            runCatching { listener.cancelNotification(notificationKey) }
        }
    }
}
