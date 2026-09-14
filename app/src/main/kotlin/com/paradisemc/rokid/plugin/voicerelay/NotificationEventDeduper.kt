package com.paradisemc.rokid.plugin.voicerelay

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import java.security.MessageDigest
import java.util.Locale

/** Remembers actual messaging events that have already been handled. */
object NotificationEventDeduper {
    private const val PREFS = "nexus_plugin_voicerelay"
    private const val KEY_SEEN = "seen_notification_events_v2"
    private const val MAX_SEEN = 300

    data class Payload(
        val sender: String,
        val text: String,
        val eventTimeMillis: Long,
        val senderPersonUri: String? = null,
        val senderPersonKey: String? = null,
        val mediaMimeType: String? = null,
        val mediaUri: String? = null,
        val voiceMessage: Boolean = false,
    )

    fun extract(sbn: StatusBarNotification, fallbackSender: String): Payload? {
        val notification = sbn.notification
        val extras = notification.extras ?: return null

        val sender = (
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)
                ?: fallbackSender
            ).toString().trim().ifBlank { fallbackSender }

        val bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (bundles != null) {
            val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles)
            val latest = messages.lastOrNull { !it.text.isNullOrBlank() }
            if (latest != null) {
                val mime = latest.dataMimeType
                val uri = latest.dataUri?.toString()
                val text = latest.text.toString()
                return Payload(
                    sender = sender,
                    text = text,
                    eventTimeMillis = latest.timestamp,
                    senderPersonUri = latest.senderPerson?.uri,
                    senderPersonKey = latest.senderPerson?.key,
                    mediaMimeType = mime,
                    mediaUri = uri,
                    voiceMessage = looksLikeVoiceMessage(text, mime),
                )
            }
        }

        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: return null
        if (text.isBlank()) return null

        val eventTime = notification.`when`.takeIf { it > 0L } ?: 0L
        return Payload(
            sender = sender,
            text = text,
            eventTimeMillis = eventTime,
            voiceMessage = looksLikeVoiceMessage(text, null),
        )
    }

    fun eventId(sbn: StatusBarNotification, payload: Payload): String {
        val conversation = sbn.notification.shortcutId
            ?.takeIf { it.isNotBlank() }
            ?: sbn.key
        val source = buildString {
            append(sbn.packageName)
            append('\u0000')
            append(conversation)
            append('\u0000')
            append(payload.eventTimeMillis)
            append('\u0000')
            append(payload.sender)
            append('\u0000')
            append(payload.text)
        }
        return sha256(source)
    }

    @Synchronized
    fun markIfNew(context: Context, eventId: String): Boolean {
        val events = load(context).toMutableList()
        if (eventId in events) return false
        events.add(eventId)
        save(context, events.takeLast(MAX_SEEN))
        return true
    }

    @Synchronized
    fun seed(context: Context, eventIds: Collection<String>) {
        if (eventIds.isEmpty()) return
        val events = load(context).toMutableList()
        for (id in eventIds) {
            if (id !in events) events.add(id)
        }
        save(context, events.takeLast(MAX_SEEN))
    }

    private fun looksLikeVoiceMessage(text: String, mimeType: String?): Boolean {
        if (mimeType?.lowercase(Locale.ROOT)?.startsWith("audio/") == true) return true
        val value = text.lowercase(Locale.ROOT).trim()
        return listOf(
            "voice message",
            "voice note",
            "sprachnachricht",
            "audio message",
            "رسالة صوتية",
            "رساله صوتيه",
            "مقطع صوتي",
            "🎤",
            "🎙",
        ).any(value::contains)
    }

    private fun load(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SEEN, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    array.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, events: List<String>) {
        val array = JSONArray()
        events.forEach(array::put)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SEEN, array.toString())
            .apply()
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
