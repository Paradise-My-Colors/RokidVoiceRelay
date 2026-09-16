package com.paradisemc.rokidcamera

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

class RemoteHttpServer(private val context: Context, private val port: Int = 8765) {
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var running = false
    fun start() {
        if (running) return
        running = true
        pool.execute { runCatching { serverSocket = ServerSocket(port); while (running) { val socket = serverSocket?.accept() ?: break; pool.execute { handle(socket) } } } }
    }
    fun stop() { running = false; runCatching { serverSocket?.close() }; pool.shutdownNow() }
    private fun handle(socket: Socket) = socket.use { s ->
        val reader = BufferedReader(InputStreamReader(s.getInputStream()))
        val requestLine = reader.readLine() ?: return@use
        val parts = requestLine.split(" "); if (parts.size < 2) return@use
        val target = parts[1]; val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.contains('=') }.associate { val kv = it.split('=', limit = 2); URLDecoder.decode(kv[0], "UTF-8") to URLDecoder.decode(kv[1], "UTF-8") }
        when (path) {
            "/status" -> json(s, 200, statusJson())
            "/open" -> { AppState.send(RemoteCommand.OpenCamera); json(s, 200, statusJson()) }
            "/action" -> { AppState.send(RemoteCommand.Action); json(s, 200, statusJson()) }
            "/toggle-mode" -> { AppState.send(RemoteCommand.ToggleMode); json(s, 200, statusJson()) }
            "/flip" -> { AppState.send(RemoteCommand.FlipLens); json(s, 200, statusJson()) }
            "/settings" -> { query["viewfinder"]?.let { AppState.setViewfinderMode(context, when (it.lowercase()) { "always" -> ViewfinderMode.ALWAYS; "video5", "video_5_seconds" -> ViewfinderMode.VIDEO_5_SECONDS; else -> ViewfinderMode.OFF }) }; json(s, 200, "{\"viewfinder\":\"${wireViewfinder()}\"}") }
            "/frame" -> { val frame = AppState.latestFrame; if (frame == null) json(s, 503, "{\"error\":\"frame unavailable\"}") else bytes(s, 200, "image/jpeg", frame) }
            else -> json(s, 404, "{\"error\":\"not found\"}")
        }
    }
    private fun statusJson() = "{\"ok\":true,\"cameraOpen\":${AppState.status.cameraOpen},\"mode\":\"${AppState.status.mode.name.lowercase()}\",\"recording\":${AppState.status.recording},\"viewfinder\":\"${wireViewfinder()}\"}"
    private fun wireViewfinder() = when (AppState.getViewfinderMode(context)) { ViewfinderMode.OFF -> "off"; ViewfinderMode.ALWAYS -> "always"; ViewfinderMode.VIDEO_5_SECONDS -> "video5" }
    private fun json(socket: Socket, code: Int, body: String) = bytes(socket, code, "application/json; charset=utf-8", body.toByteArray())
    private fun bytes(socket: Socket, code: Int, type: String, body: ByteArray) { val out = socket.getOutputStream(); val header = "HTTP/1.1 $code ${if (code == 200) "OK" else "ERROR"}\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nAccess-Control-Allow-Origin: *\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"; out.write(header.toByteArray()); out.write(body); out.flush() }
}
