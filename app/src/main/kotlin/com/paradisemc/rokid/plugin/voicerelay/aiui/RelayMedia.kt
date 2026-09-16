package com.paradisemc.rokid.plugin.voicerelay.aiui

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.paradisemc.rokid.plugin.voicerelay.IncomingMessage
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

object RelayMedia {
    const val MAX_BYTES = 12 * 1024 * 1024
    private val io = Executors.newSingleThreadExecutor()
    fun directory(c: Context) = File(c.filesDir, "aiui-audio").apply { mkdirs() }
    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun id(m: IncomingMessage): String = digest((m.stableKey() + ":" + m.receivedAt).toByteArray()).take(24)
    fun newFile(c: Context, suffix: String) = File(directory(c), UUID.randomUUID().toString() + suffix)
    fun uri(c: Context, file: File): Uri = Uri.parse("content://${c.packageName}.audio/${file.name}")
    fun cache(c: Context, source: Uri): File {
        require(source.scheme == "content" || source.scheme == "file") { "Unsupported audio source" }
        val file = newFile(c, ".audio")
        try {
            c.contentResolver.openInputStream(source)?.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(16384); var total = 0
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        total += n; require(total <= MAX_BYTES) { "Audio exceeds 12 MB" }
                        output.write(buffer, 0, n)
                    }
                }
            } ?: error("The messaging app did not grant audio access")
            require(file.length() > 0) { "Audio is empty" }
            return file
        } catch (e: Throwable) { file.delete(); throw e }
    }
    fun captureNotificationMedia(c: Context, m: IncomingMessage) {
        if (m.mediaUri.isNullOrBlank() || !m.mediaMimeType.orEmpty().startsWith("audio/")) return
        io.execute {
            runCatching {
                val file = cache(c, Uri.parse(m.mediaUri))
                c.getSharedPreferences("aiui-media", 0).edit().putString(id(m), file.name).apply()
            }
        }
    }
    fun cached(c: Context, m: IncomingMessage): File? {
        val name = c.getSharedPreferences("aiui-media", 0).getString(id(m), null) ?: return null
        return File(directory(c), name).takeIf { it.isFile }
    }
    fun mime(file: File): String {
        val head = ByteArray(40); val n = file.inputStream().use { it.read(head) }
        return when {
            n >= 36 && String(head, 0, 4) == "OggS" -> "audio/ogg"
            n >= 12 && String(head, 0, 4) == "RIFF" && String(head, 8, 4) == "WAVE" -> "audio/wav"
            n >= 12 && String(head, 4, 4) == "ftyp" -> "audio/mp4"
            else -> "audio/mpeg"
        }
    }
    fun cleanup(c: Context) {
        val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        directory(c).listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }
}

/** Only explicit, temporary URI grants allow another app to open audio. */
class RelayAudioProvider : ContentProvider() {
    override fun onCreate() = true
    private fun resolve(uri: Uri): File {
        val root = RelayMedia.directory(requireNotNull(context)).canonicalFile
        val file = File(root, uri.lastPathSegment ?: error("Missing file")).canonicalFile
        require(file.parentFile == root && file.isFile) { "Invalid audio file" }
        return file
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r")
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri) = RelayMedia.mime(resolve(uri))
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val file = resolve(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map { when (it) { OpenableColumns.DISPLAY_NAME -> "Voice reply." + when(RelayMedia.mime(file)){"audio/ogg"->"ogg";"audio/wav"->"wav";"audio/mp4"->"m4a";else->"mp3"}; OpenableColumns.SIZE -> file.length(); else -> null } }) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
