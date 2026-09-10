package com.paradisemc.rokid.plugin.voicerelay

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import kotlin.math.roundToInt

class WavRecorder(private val context: Context) {
    private val sampleRate = 16_000
    private val channels = 1
    private val bitsPerSample = 16
    private val tempFile = File.createTempFile("rokid_voice_", ".wav", context.cacheDir)
    private val raf = RandomAccessFile(tempFile, "rw")
    private var pcmBytes: Long = 0
    private var closed = false

    init {
        raf.write(ByteArray(44))
    }

    @Synchronized
    fun write(pcm: ByteArray) {
        if (closed) return
        val boosted = applyGainPcm16Le(pcm, 4.0f)
        raf.write(boosted)
        pcmBytes += boosted.size
    }

    @Synchronized
    fun finishAndPublish(): PublishedRecording? {
        if (closed) return null
        closed = true
        writeHeader()
        raf.close()

        val durationMs =
            pcmBytes * 1000L / (sampleRate * channels * bitsPerSample / 8)
        val name = "RokidVoice_${System.currentTimeMillis()}.wav"
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(
                MediaStore.Audio.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_MUSIC}/RokidVoiceRelay",
            )
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: run {
                tempFile.delete()
                return null
            }

        return try {
            resolver.openOutputStream(uri, "w")?.use { out ->
                FileInputStream(tempFile).use { input -> input.copyTo(out) }
            } ?: throw IllegalStateException("Could not open MediaStore output")

            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                null,
                null,
            )
            tempFile.delete()
            PublishedRecording(uri.toString(), name, durationMs)
        } catch (_: Throwable) {
            resolver.delete(uri, null, null)
            tempFile.delete()
            null
        }
    }

    @Synchronized
    fun discard() {
        if (!closed) {
            closed = true
            runCatching { raf.close() }
        }
        tempFile.delete()
    }

    private fun writeHeader() {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val fileSizeMinus8 = 36L + pcmBytes

        raf.seek(0)
        raf.writeBytes("RIFF")
        writeLe32(fileSizeMinus8)
        raf.writeBytes("WAVE")
        raf.writeBytes("fmt ")
        writeLe32(16)
        writeLe16(1)
        writeLe16(channels)
        writeLe32(sampleRate.toLong())
        writeLe32(byteRate.toLong())
        writeLe16(blockAlign)
        writeLe16(bitsPerSample)
        raf.writeBytes("data")
        writeLe32(pcmBytes)
    }

    private fun writeLe16(value: Int) {
        raf.write(value and 0xFF)
        raf.write((value ushr 8) and 0xFF)
    }

    private fun writeLe32(value: Long) {
        raf.write((value and 0xFF).toInt())
        raf.write(((value ushr 8) and 0xFF).toInt())
        raf.write(((value ushr 16) and 0xFF).toInt())
        raf.write(((value ushr 24) and 0xFF).toInt())
    }

    private fun applyGainPcm16Le(input: ByteArray, gain: Float): ByteArray {
        val output = input.copyOf()
        var i = 0
        while (i + 1 < output.size) {
            val low = output[i].toInt() and 0xFF
            val high = output[i + 1].toInt()
            val sample = (high shl 8) or low
            val amplified = (sample * gain).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output[i] = (amplified and 0xFF).toByte()
            output[i + 1] = ((amplified shr 8) and 0xFF).toByte()
            i += 2
        }
        return output
    }
}

data class PublishedRecording(
    val uri: String,
    val name: String,
    val durationMs: Long,
) {
    fun delete(context: Context) {
        runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) }
    }
}
