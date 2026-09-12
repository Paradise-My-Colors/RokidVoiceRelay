package com.paradisemc.rokid.plugin.voicerelay

import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
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

        val eventId = NotificationEventDeduper.eventId(sbn, payload)
        if (!NotificationEventDeduper.markIfNew(this, eventId)) return

        val message = IncomingMessage(
            app = app,
            packageName = sbn.packageName,
            notificationKey = sbn.key,
            shortcutId = sbn.notification.shortcutId,
            sender = payload.sender,
            text = payload.text,
            senderPersonUri = payload.senderPersonUri,
            senderPersonKey = payload.senderPersonKey,
        )
        PendingMessageStore.setLastCaptured(this, message)

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

    private fun trySendAudioDataReply(
        target: IncomingMessage,
        uri: Uri,
        mimeType: String,
    ): Boolean = runCatching {
        val candidates = activeNotifications.filter { sbn ->
            sbn.packageName == target.packageName && (
                (!target.notificationKey.isNullOrBlank() && sbn.key == target.notificationKey) ||
                    (!target.shortcutId.isNullOrBlank() && sbn.notification.shortcutId == target.shortcutId)
                )
        }
        val notification = candidates.firstOrNull()?.notification ?: return@runCatching false
        val actions = notification.actions ?: return@runCatching false

        val actionAndInput = actions.asSequence()
            .flatMap { action ->
                (action.remoteInputs ?: emptyArray()).asSequence().map { input -> action to input }
            }
            .firstOrNull { (_, input) ->
                input.allowedDataTypes.any { allowed -> mimeMatches(allowed, mimeType) }
            } ?: return@runCatching false

        val (action, input) = actionAndInput
        grantUriPermission(target.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val fillIn = Intent().apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newUri(contentResolver, "Voice Relay audio", uri)
        }
        RemoteInput.addDataResultToIntent(input, fillIn, mapOf(mimeType to uri))
        action.actionIntent.send(this, 0, fillIn)
        true
    }.getOrDefault(false)

    companion object {
        @Volatile private var current: VoiceRelayNotificationListener? = null

        fun dismissNotification(notificationKey: String?) {
            if (notificationKey.isNullOrBlank()) return
            val listener = current ?: return
            runCatching { listener.cancelNotification(notificationKey) }
        }

        fun sendAudioDataReply(target: IncomingMessage, uri: Uri, mimeType: String): Boolean {
            val listener = current ?: return false
            return listener.trySendAudioDataReply(target, uri, mimeType)
        }

        private fun mimeMatches(allowed: String, actual: String): Boolean {
            if (allowed == actual || allowed == "*/*") return true
            if (allowed.endsWith("/*")) {
                return actual.startsWith(allowed.substringBefore('/').plus('/'))
            }
            return false
        }
    }
}
