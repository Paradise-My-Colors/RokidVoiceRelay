package com.paradisemc.rokid.plugin.voicerelay

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.PowerManager

/**
 * Phone-side rules that decide whether an incoming message is allowed to wake
 * or display a notice on the Rokid glasses. Messages are still stored in the
 * Voice Relay inbox when a HUD notice is suppressed.
 */
object NotificationDisplayPreferences {
    private const val PREFS = "nexus_plugin_voicerelay_display"
    private const val KEY_RESPECT_SILENT = "respect_phone_silent"
    private const val KEY_HIDE_WHEN_UNLOCKED = "hide_when_phone_unlocked"

    fun respectDnd(context: Context): Boolean = prefs(context).getBoolean("respect_dnd", true)
    fun setRespectDnd(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean("respect_dnd", enabled).apply() }
    fun telegramEnabled(context: Context): Boolean = prefs(context).getBoolean("telegram_enabled", true)
    fun whatsappEnabled(context: Context): Boolean = prefs(context).getBoolean("whatsapp_enabled", true)
    fun nexusNotices(context: Context): Boolean = prefs(context).getBoolean("nexus_notices", true)
    fun setOption(context: Context, key: String, enabled: Boolean) {
        require(key in setOf("respect_dnd", "respect_phone_silent", "hide_when_phone_unlocked", "telegram_enabled", "whatsapp_enabled", "nexus_notices"))
        prefs(context).edit().putBoolean(key, enabled).apply()
    }
    fun appEnabled(context: Context, pkg: String?): Boolean = when (pkg) {
        "com.whatsapp", "com.whatsapp.w4b" -> whatsappEnabled(context)
        "org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.messenger.beta", "org.thunderdog.challegram" -> telegramEnabled(context)
        else -> true
    }
    fun isDnd(context: Context): Boolean = runCatching {
        val filter = context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
        // Unknown state is treated as quiet when respecting DND.
        filter != NotificationManager.INTERRUPTION_FILTER_ALL
    }.getOrDefault(true)

    fun respectPhoneSilent(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RESPECT_SILENT, true)

    fun setRespectPhoneSilent(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_RESPECT_SILENT, enabled).apply()
    }

    fun hideWhenPhoneUnlocked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HIDE_WHEN_UNLOCKED, false)

    fun setHideWhenPhoneUnlocked(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HIDE_WHEN_UNLOCKED, enabled).apply()
    }

    fun shouldShowOnGlasses(context: Context): Boolean {
        if (respectDnd(context) && isDnd(context)) return false
        if (respectPhoneSilent(context) && isPhoneSilent(context)) return false
        if (hideWhenPhoneUnlocked(context) && isPhoneUnlockedAndInteractive(context)) return false
        return true
    }

    fun isPhoneSilent(context: Context): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        return audio.ringerMode == AudioManager.RINGER_MODE_SILENT
    }

    /**
     * "Unlocked" here means the phone display is actively on and the device is
     * no longer behind the keyguard. An unlocked phone with its display asleep
     * is therefore allowed to relay notifications.
     */
    fun isPhoneUnlockedAndInteractive(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        if (!power.isInteractive) return false

        val keyguard = context.getSystemService(KeyguardManager::class.java) ?: return false
        return !keyguard.isDeviceLocked
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
