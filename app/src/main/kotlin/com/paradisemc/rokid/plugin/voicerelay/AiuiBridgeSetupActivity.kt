package com.paradisemc.rokid.plugin.voicerelay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class AiuiBridgeSetupActivity : Activity() {
    private val requestCode = 9041

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(42, 42, 42, 42) }
        layout.addView(TextView(this).apply {
            text = "Voice Relay AIUI bridge\n\nGrant Nearby devices so Rokid AIUI can connect directly to this phone over Bluetooth. No fixed Wi-Fi address is used."
            textSize = 18f
        })
        layout.addView(Button(this).apply {
            text = "Enable Bluetooth bridge"; isAllCaps = false; setOnClickListener { ensurePermissions() }
        })
        layout.addView(Button(this).apply {
            text = "Open Voice Relay settings"; isAllCaps = false
            setOnClickListener { startActivity(Intent(this@AiuiBridgeSetupActivity, VoiceRelaySettingsActivity::class.java)) }
        })
        setContentView(layout)
        ensurePermissions()
    }

    private fun ensurePermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) { AiuiBleBridge.start(applicationContext); return }
        val permissions = arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) AiuiBleBridge.start(applicationContext) else requestPermissions(missing.toTypedArray(), requestCode)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == this.requestCode && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            AiuiBleBridge.start(applicationContext)
        }
    }
}
