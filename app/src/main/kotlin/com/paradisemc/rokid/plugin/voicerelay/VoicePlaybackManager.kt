package com.paradisemc.rokid.plugin.voicerelay

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import com.paradisemc.rokid.plugin.voicerelay.telegram.TelegramClientManager
import com.paradisemc.rokid.plugin.voicerelay.telegram.TelegramVoiceSender
import android.os.Handler
import android.os.Looper
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
        if (mediaUri == null && TelegramVoiceSender.isTelegram(message)) {
            val manager = TelegramClientManager.get(context)
            manager.start()
            manager.listVoiceNotes(message) { listed ->
                listed.fold(
                    onFailure = { callback(Result.failure(it)) },
                    onSuccess = { notes ->
                        if (notes.length() == 0) {
                            callback(Result.failure(IllegalStateException("No recent incoming Telegram voice note was found for this conversation.")))
                        } else {
                            val id = notes.getJSONObject(0).optString("message").toLongOrNull()
                            if (id == null) callback(Result.failure(IllegalStateException("Telegram returned an invalid voice-note id.")))
                            else manager.downloadVoice(message, id) { downloaded ->
                                downloaded.fold(
                                    onFailure = { callback(Result.failure(it)) },
                                    onSuccess = { file -> Handler(Looper.getMainLooper()).post { playSource(context, file.absolutePath, null, callback) } },
                                )
                            }
                        }
                    },
                )
            }
            return
        }
        if (mediaUri == null) {
            callback(Result.failure(IllegalStateException("WhatsApp identified this as a voice message, but WhatsApp did not expose the encrypted audio file to Android. Open/share the message on the phone if playback is needed.")))
            return
        }

        playSource(context, null, mediaUri, callback)
    }

    private fun playSource(
        context: Context,
        filePath: String?,
        mediaUri: String?,
        callback: (Result<Unit>) -> Unit,
    ) {
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
                if (filePath != null) player.setDataSource(filePath) else player.setDataSource(appContext, Uri.parse(requireNotNull(mediaUri)))
                player.setOnCompletionListener {
                    if (active.compareAndSet(it, null)) it.release()
                    callback(Result.success(Unit))
                }
                player.setOnErrorListener { mp, what, extra ->
                    if (active.compareAndSet(mp, null)) mp.release()
                    callback(Result.failure(IllegalStateException("Voice playback failed ($what/$extra).")))
                    true
                }
                player.setOnPreparedListener { it.start() }
                player.prepareAsync()
            }
        }.onFailure { error -> callback(Result.failure(error)) }
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
