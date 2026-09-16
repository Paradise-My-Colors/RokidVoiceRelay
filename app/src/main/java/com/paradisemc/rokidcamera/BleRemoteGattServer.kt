package com.paradisemc.rokidcamera

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.util.UUID

class BleRemoteGattServer(private val context: Context) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var gattServer: BluetoothGattServer? = null
    private var advertising = false

    fun start(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val connectGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            val advertiseGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
            if (!connectGranted || !advertiseGranted) return false
        }
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return false
        val adapter = bluetoothManager.adapter ?: return false
        if (!adapter.isEnabled) return false

        if (gattServer == null) {
            gattServer = bluetoothManager.openGattServer(context, callback) ?: return false
            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(
                BluetoothGattCharacteristic(
                    COMMAND_UUID,
                    BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE
                )
            )
            service.addCharacteristic(
                BluetoothGattCharacteristic(
                    STATUS_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ
                )
            )
            gattServer?.addService(service)
        }

        if (!advertising) {
            val advertiser = adapter.bluetoothLeAdvertiser ?: return false
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .build()
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
            advertiser.startAdvertising(settings, data, advertiseCallback)
            advertising = true
        }
        return true
    }

    fun stop() {
        val adapter = bluetoothManager.adapter
        if (advertising && adapter != null && (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED)) {
            runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        }
        advertising = false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            runCatching { gattServer?.close() }
        }
        gattServer = null
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            var result = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
            if (characteristic.uuid == COMMAND_UUID && !preparedWrite && offset == 0 && value.isNotEmpty()) {
                result = if (handleCommand(value[0].toInt() and 0xff)) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
            }
            if (responseNeeded && canConnect()) {
                runCatching { gattServer?.sendResponse(device, requestId, result, 0, null) }
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            if (!canConnect()) return
            if (characteristic.uuid != STATUS_UUID) {
                runCatching { gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null) }
                return
            }
            val payload = statusPayload()
            if (offset > payload.size) {
                runCatching { gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null) }
            } else {
                runCatching { gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, payload.copyOfRange(offset, payload.size)) }
            }
        }
    }

    private fun handleCommand(command: Int): Boolean = when (command) {
        CMD_OPEN -> { AppState.send(RemoteCommand.OpenCamera); true }
        CMD_ACTION -> { AppState.send(RemoteCommand.Action); true }
        CMD_TOGGLE_MODE -> { AppState.send(RemoteCommand.ToggleMode); true }
        CMD_FLIP -> { AppState.send(RemoteCommand.FlipLens); true }
        else -> false
    }

    private fun statusPayload(): ByteArray {
        val viewfinder = when (AppState.getViewfinderMode(context)) {
            ViewfinderMode.OFF -> 0
            ViewfinderMode.ALWAYS -> 1
            ViewfinderMode.VIDEO_5_SECONDS -> 2
        }
        return byteArrayOf(
            PROTOCOL_VERSION.toByte(),
            if (AppState.status.cameraOpen) 1 else 0,
            if (AppState.status.mode == CameraMode.VIDEO) 1 else 0,
            if (AppState.status.recording) 1 else 0,
            viewfinder.toByte()
        )
    }

    private fun canConnect(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) { advertising = false }
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("f9c10000-7e8b-4c88-9f3a-52d1f183a001")
        val COMMAND_UUID: UUID = UUID.fromString("f9c10001-7e8b-4c88-9f3a-52d1f183a001")
        val STATUS_UUID: UUID = UUID.fromString("f9c10002-7e8b-4c88-9f3a-52d1f183a001")
        const val PROTOCOL_VERSION = 1
        const val CMD_OPEN = 1
        const val CMD_ACTION = 2
        const val CMD_TOGGLE_MODE = 3
        const val CMD_FLIP = 4
    }
}
