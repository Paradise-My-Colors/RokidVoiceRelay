package com.paradisemc.rokid.plugin.voicerelay

import android.app.NotificationManager
import android.content.Context

object AiuiNotificationPreferences {
    private const val PREFS = "voice_relay_aiui"
    private const val KEY_RESPECT_DND = "respect_dnd"
    private const val KEY_TELEGRAM = "telegram_enabled"
    private const val KEY_WHATSAPP = "whatsapp_enabled"

    fun showWhenPhoneUnlocked(context: Context): Boolean = !NotificationDisplayPreferences.hideWhenPhoneUnlocked(context)
    fun setShowWhenPhoneUnlocked(context: Context, enabled: Boolean) = NotificationDisplayPreferences.setHideWhenPhoneUnlocked(context, !enabled)
    fun respectDnd(context: Context): Boolean = prefs(context).getBoolean(KEY_RESPECT_DND, true)
    fun setRespectDnd(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean(KEY_RESPECT_DND, enabled).apply() }
    fun telegramEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_TELEGRAM, true)
    fun setTelegramEnabled(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean(KEY_TELEGRAM, enabled).apply() }
    fun whatsappEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_WHATSAPP, true)
    fun setWhatsappEnabled(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean(KEY_WHATSAPP, enabled).apply() }

    fun isAppEnabled(context: Context, packageName: String): Boolean = when (packageName) {
        "com.whatsapp", "com.whatsapp.w4b" -> whatsappEnabled(context)
        "org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.messenger.beta", "org.thunderdog.challegram" -> telegramEnabled(context)
        else -> false
    }

    fun dndAllowsRelay(context: Context): Boolean {
        if (!respectDnd(context)) return true
        val manager = context.getSystemService(NotificationManager::class.java) ?: return true
        return manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
    }

    fun settingsJson(context: Context) = org.json.JSONObject()
        .put("showUnlocked", showWhenPhoneUnlocked(context))
        .put("respectDnd", respectDnd(context))
        .put("telegramEnabled", telegramEnabled(context))
        .put("whatsappEnabled", whatsappEnabled(context))

    fun set(context: Context, key: String, value: Boolean): Boolean {
        when (key) {
            "showUnlocked" -> setShowWhenPhoneUnlocked(context, value)
            "respectDnd" -> setRespectDnd(context, value)
            "telegramEnabled" -> setTelegramEnabled(context, value)
            "whatsappEnabled" -> setWhatsappEnabled(context, value)
            else -> return false
        }
        return true
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
