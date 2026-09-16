package com.paradisemc.rokidcamera

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings

class RemoteControllerService : Service() {
    private var httpServer: RemoteHttpServer? = null
    private var bleServer: BleRemoteGattServer? = null
    private val commandListener: (RemoteCommand) -> Unit = { if (it is RemoteCommand.OpenCamera) openCameraActivity() }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Rokid Camera Remote")
                .setContentText("Bluetooth remote ready for Rokid glasses")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build()
        } else {
            Notification.Builder(this).setContentTitle("Rokid Camera Remote").setSmallIcon(android.R.drawable.ic_menu_camera).build()
        }
        startForeground(41, notification)
        AppState.subscribe(commandListener)
        httpServer = RemoteHttpServer(this).also { it.start() }
        bleServer = BleRemoteGattServer(this)
        bleServer?.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        bleServer?.start()
        return START_STICKY
    }

    private fun openCameraActivity() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            runCatching {
                startActivity(Intent(this, CameraActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
        }
    }

    override fun onDestroy() {
        AppState.unsubscribe(commandListener)
        bleServer?.stop()
        httpServer?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel(CHANNEL_ID, "Rokid camera bridge", NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object { const val CHANNEL_ID = "rokid_camera_bridge" }
}
