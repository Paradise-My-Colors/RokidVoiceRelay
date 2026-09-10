package com.paradisemc.rokid.plugin.voicerelay

import android.app.KeyguardManager
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
