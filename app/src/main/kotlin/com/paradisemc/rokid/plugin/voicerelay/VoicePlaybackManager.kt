package com.paradisemc.rokid.plugin.voicerelay

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import com.paradisemc.rokid.plugin.voicerelay.telegram.TelegramVoiceSender
import java.util.concurrent.atomic.AtomicReference

/**
 * Plays notification-exposed voice media only when Android has a Bluetooth
 * audio output. It deliberately never falls back to the phone loudspeaker.
 *
 * WhatsApp normally does not expose the encrypted voice-note file through its
 * notification. Telegram may expose a data URI on some builds; otherwise a
 * future TDLib media fetch can be added without changing the HUD contract.
 */
object VoicePlaybackManager {
    private val active = AtomicReference<MediaPlayer?>(null)

    fun canOffer(message: IncomingMessage?): Boolean = message?.voiceMessage == true

    fun play(
        context: Context,
        message: IncomingMessage,
        callback: (Result<Unit>) -> Unit,
    ) {
        val mediaUri = message.mediaUri?.takeIf { it.isNotBlank() }
        if (mediaUri == null) {
            val detail = when {
                TelegramVoiceSender.isTelegram(message) ->
                    "Telegram identified this as a voice message, but this Telegram notification did not expose its audio file to Android."
                else ->
                    "WhatsApp identified this as a voice message, but WhatsApp did not expose the encrypted audio file to Android."
            }
            callback(Result.failure(IllegalStateException(detail)))
            return
        }

        val appContext = context.applicationContext
        val audioManager = appContext.getSystemService(AudioManager::class.java)
        val bluetoothOutput = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.firstOrNull(::isBluetoothAudioOutput)
        if (bluetoothOutput == null) {
            callback(
                Result.failure(
                    IllegalStateException(
                        "No Bluetooth audio output is available. Playback was blocked so the voice message would not play from the phone speaker.",
                    ),
                ),
            )
            return
        }

        runCatching {
            active.getAndSet(null)?.let { old ->
                runCatching { old.stop() }
                old.release()
            }

            MediaPlayer().also { player ->
                active.set(player)
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                player.preferredDevice = bluetoothOutput
                player.setDataSource(appContext, Uri.parse(mediaUri))
                player.setOnCompletionListener {
                    if (active.compareAndSet(it, null)) it.release()
                    callback(Result.success(Unit))
                }
                player.setOnErrorListener { mp, what, extra ->
                    if (active.compareAndSet(mp, null)) mp.release()
                    callback(Result.failure(IllegalStateException("Voice playback failed ($what/$extra).")))
                    true
                }
                player.prepareAsync()
                player.setOnPreparedListener { it.start() }
            }
        }.onFailure(callback)
    }

    fun stop() {
        active.getAndSet(null)?.let { player ->
            runCatching { player.stop() }
            player.release()
        }
    }

    private fun isBluetoothAudioOutput(device: AudioDeviceInfo): Boolean = when (device.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> true
        else -> Build.VERSION.SDK_INT >= 31 && (
            device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
            )
    }
}
