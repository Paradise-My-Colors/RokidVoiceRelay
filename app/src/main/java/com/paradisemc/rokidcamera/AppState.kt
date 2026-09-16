package com.paradisemc.rokidcamera

import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList

enum class CameraMode { PHOTO, VIDEO }
enum class ViewfinderMode { OFF, ALWAYS, VIDEO_5_SECONDS }

data class CameraStatus(var mode: CameraMode = CameraMode.PHOTO, var recording: Boolean = false, var cameraOpen: Boolean = false)

sealed interface RemoteCommand {
    data object Action : RemoteCommand
    data object ToggleMode : RemoteCommand
    data object OpenCamera : RemoteCommand
    data object FlipLens : RemoteCommand
}

object AppState {
    val status = CameraStatus()
    @Volatile var latestFrame: ByteArray? = null
    private val listeners = CopyOnWriteArrayList<(RemoteCommand) -> Unit>()
    fun subscribe(listener: (RemoteCommand) -> Unit) { listeners += listener }
    fun unsubscribe(listener: (RemoteCommand) -> Unit) { listeners -= listener }
    fun send(command: RemoteCommand) = listeners.forEach { it(command) }
    fun getViewfinderMode(context: Context): ViewfinderMode {
        val value = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("viewfinderMode", ViewfinderMode.OFF.name) ?: ViewfinderMode.OFF.name
        return runCatching { ViewfinderMode.valueOf(value) }.getOrDefault(ViewfinderMode.OFF)
    }
    fun setViewfinderMode(context: Context, mode: ViewfinderMode) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("viewfinderMode", mode.name).apply()
    }
}
