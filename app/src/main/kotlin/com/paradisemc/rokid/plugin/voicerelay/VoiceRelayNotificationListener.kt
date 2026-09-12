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

    override fun onCreate() {
        super.onCreate()
        current = this
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        current = this
        PendingMessageStore.setListenerState(this, true)

        // Android gives a newly connected listener all notifications that are
        // already active. Mark their current message events as seen so an app
        // update/restart never turns old unread messages into fresh HUD popups.
        seedExistingNotificationEvents()

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
        if (!isSupportedConversationNotification(sbn)) return

        val app = appNameForPackage(sbn.packageName) ?: return
        val payload = NotificationEventDeduper.extract(sbn, app) ?: return
        if (payload.text.isBlank()) return

        // Telegram may re-post the exact same underlying message as an hourly
        // reminder, and may refresh every active chat notification when one
        // genuinely new message arrives. Only an unseen message event is
        // allowed to enter the HUD delivery path.
        val eventId = NotificationEventDeduper.eventId(sbn, payload)
        if (!NotificationEventDeduper.markIfNew(this, eventId)) return

        val message = IncomingMessage(
            app = app,
            packageName = sbn.packageName,
            notificationKey = sbn.key,
            shortcutId = sbn.notification.shortcutId,
            sender = payload.sender,
            text = payload.text,
        )
        PendingMessageStore.setLastCaptured(this, message)

        // Keep suppressed messages available in the Voice Relay inbox, but do
        // not connect to Nexus, wake the glasses, or update an active HUD card.
        if (!NotificationDisplayPreferences.shouldShowOnGlasses(this)) {
            PendingMessageStore.put(this, message)
            return
        }

        runtime.show(message)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        if (appNameForPackage(sbn.packageName) == null) return
        PendingMessageStore.removeByNotificationKey(this, sbn.key)
        VoiceRelayPluginService.notifyInboxChanged()
    }

    private fun seedExistingNotificationEvents() {
        val ids = runCatching {
            activeNotifications
                .asSequence()
                .filter(::isSupportedConversationNotification)
                .mapNotNull { sbn ->
                    val app = appNameForPackage(sbn.packageName) ?: return@mapNotNull null
                    val payload = NotificationEventDeduper.extract(sbn, app) ?: return@mapNotNull null
                    NotificationEventDeduper.eventId(sbn, payload)
                }
                .toList()
        }.getOrDefault(emptyList())
        NotificationEventDeduper.seed(this, ids)
    }

    private fun isSupportedConversationNotification(sbn: StatusBarNotification): Boolean {
        if (appNameForPackage(sbn.packageName) == null) return false
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (sbn.isOngoing) return false
        return true
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

    companion object {
        @Volatile private var current: VoiceRelayNotificationListener? = null

        fun dismissNotification(notificationKey: String?) {
            if (notificationKey.isNullOrBlank()) return
            val listener = current ?: return
            runCatching { listener.cancelNotification(notificationKey) }
        }
    }
}
