package com.example.meshtalk.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlin.math.log2

@SuppressLint("MissingPermission")
class BleAdvertiser(private val activity: AppCompatActivity) {

    val bluetoothManager = activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var adapter = bluetoothManager.adapter
    private var advertiser = adapter.bluetoothLeAdvertiser

    private var isAdvertising = false
    private var callback: AdvertiseCallback? = null

    // Your custom BLE service UUID
    private val serviceUUID = ParcelUuid.fromString("0000abcd-0000-1000-8000-00805f9b34fb")

    fun startAdvertising() {
        if (isAdvertising) return

        // Check permissions (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_ADVERTISE)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(activity,
                    arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE), 2001)
                return
            }
        }

        if (advertiser == null) {
            Log.e("BLE_ADV", "BLE Advertising not supported")
            return
        }

        // Settings
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)       // allow central devices to connect
            .build()

        // What we broadcast
        val data = AdvertiseData.Builder()
            .addServiceData(serviceUUID, byteArrayOf(0x01, 0x02, 0x03))
            .addServiceUuid(serviceUUID)
            .setIncludeDeviceName(true) // show name
            .build()

        // Optional scan response
//        val scanResponse = AdvertiseData.Builder()
//            .addServiceData(serviceUUID, byteArrayOf(1, 2, 3))
//            .build()

        callback = object : AdvertiseCallback() {
            override fun onStartSuccess(result: AdvertiseSettings) {
                super.onStartSuccess(result)
                Log.d("BLE_ADV", "Advertising started")
                isAdvertising = true
            }

            override fun onStartFailure(errorCode: Int) {
                super.onStartFailure(errorCode)
                Log.e("BLE_ADV", "Advertising failed: $errorCode")
                isAdvertising = false
            }
        }

        advertiser.startAdvertising(settings, data, callback)
        Log.d("BLE_ADV", "Advertising started $settings $data")
    }

    fun stopAdvertising() {
        if (!isAdvertising) return
        advertiser?.stopAdvertising(callback)
        Log.d("BLE_ADV", "Advertising stopped")
        isAdvertising = false
    }


    fun isRunning() = isAdvertising
}