package com.paradisemc.rokid.plugin.voicerelay.telegram

import android.content.Context
import com.paradisemc.rokid.plugin.voicerelay.IncomingMessage
import com.paradisemc.rokid.plugin.voicerelay.PublishedRecording

object TelegramVoiceSender {
    fun isTelegram(target: IncomingMessage?): Boolean =
        target?.packageName?.startsWith("org.telegram.") == true

    fun send(
        context: Context,
        target: IncomingMessage,
        recording: PublishedRecording,
        callback: (Result<Long>) -> Unit,
    ) {
        if (!isTelegram(target)) {
            callback(Result.failure(IllegalStateException("This sender currently supports Telegram only.")))
            return
        }

        val manager = TelegramClientManager.get(context)
        manager.start()
        if (!manager.isReady()) {
            callback(
                Result.failure(
                    IllegalStateException(
                        "Telegram is not connected. Open Voice Relay on the phone and complete Telegram setup.",
                    ),
                ),
            )
            return
        }

        OpusVoiceEncoder.encode(context.applicationContext, recording.uri, recording.durationMs) { encoded ->
            encoded.fold(
                onSuccess = { voice ->
                    manager.sendVoiceNote(
                        target = target,
                        oggPath = voice.file.absolutePath,
                        durationSeconds = voice.durationSeconds,
                    ) { result ->
                        voice.file.delete()
                        callback(result)
                    }
                },
                onFailure = { callback(Result.failure(it)) },
            )
        }
    }
}
