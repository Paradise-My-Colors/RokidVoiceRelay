package com.paradisemc.rokid.plugin.voicerelay.whatsapp

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import com.paradisemc.rokid.plugin.voicerelay.IncomingMessage
import com.paradisemc.rokid.plugin.voicerelay.PublishedRecording
import com.paradisemc.rokid.plugin.voicerelay.VoiceRelayNotificationListener

data class WhatsAppSendOutcome(
    val completedInBackground: Boolean,
    val detail: String,
)

object WhatsAppVoiceSender {
    fun isWhatsApp(target: IncomingMessage?): Boolean = when (target?.packageName) {
        "com.whatsapp", "com.whatsapp.w4b" -> true
        else -> false
    }

    fun send(
        context: Context,
        target: IncomingMessage,
        recording: PublishedRecording,
        callback: (Result<WhatsAppSendOutcome>) -> Unit,
    ) {
        if (!isWhatsApp(target)) {
            callback(Result.failure(IllegalArgumentException("This sender supports WhatsApp only.")))
            return
        }

        val uri = Uri.parse(recording.uri)

        // Best route: the notification's exact reply PendingIntent. Android
        // RemoteInput can carry binary data when the receiving app advertises
        // an audio MIME type, keeping the whole transaction in the background.
        if (VoiceRelayNotificationListener.sendAudioDataReply(target, uri, "audio/wav")) {
            callback(
                Result.success(
                    WhatsAppSendOutcome(
                        completedInBackground = true,
                        detail = "Audio reply was handed to the exact WhatsApp notification conversation.",
                    ),
                ),
            )
            return
        }

        // Second route: Android's standard voice-message-to-contact contract.
        // This can carry audio plus an app-specific conversation/chat id. A
        // receiving app may still choose to present a phone confirmation UI.
        callback(runCatching { launchVoiceMessageIntent(context, target, uri) })
    }

    private fun launchVoiceMessageIntent(
        context: Context,
        target: IncomingMessage,
        uri: Uri,
    ): WhatsAppSendOutcome {
        val packageName = target.packageName ?: error("WhatsApp package is unavailable.")
        val chatId = target.shortcutId
            ?.takeIf { it.isNotBlank() }
            ?: target.senderPersonKey?.takeIf { it.isNotBlank() }
            ?: target.senderPersonUri?.removePrefix("tel:")?.takeIf { it.isNotBlank() }

        val intent = Intent(ContactsContract.Intents.ACTION_VOICE_SEND_MESSAGE_TO_CONTACTS).apply {
            setPackage(packageName)
            type = "audio/wav"
            clipData = ClipData.newUri(context.contentResolver, "Rokid voice note", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(ContactsContract.Intents.EXTRA_RECIPIENT_CONTACT_NAME, arrayOf(target.sender))
            if (chatId != null) {
                putExtra(ContactsContract.Intents.EXTRA_RECIPIENT_CONTACT_CHAT_ID, arrayOf(chatId))
            }
        }

        val handlers = context.packageManager.queryIntentActivities(intent, 0)
        if (handlers.isEmpty()) {
            error(
                "This WhatsApp version does not expose Android's voice-message intent, " +
                    "and its notification reply action is text-only.",
            )
        }

        context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
        return WhatsAppSendOutcome(
            completedInBackground = false,
            detail = "WhatsApp accepted Android's voice-message intent. Check the phone: this WhatsApp version requires its own confirmation before sending.",
        )
    }
}
