package com.example.dualroleble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import java.nio.charset.Charset
import java.util.*

/**
 * DualRoleBleManager
 * - Starts a GATT Server (Peripheral) exposing a simple service with a WRITE (RX) characteristic
 *   and a NOTIFY (TX) characteristic.
 * - Starts scanning + GATT Client to connect to peers that advertise the same service.
 *
 * NOTE: This file is a single-file, minimal dual-role manager meant as a starting point.
 * You must request runtime permissions (BLUETOOTH_SCAN, BLUETOOTH_CONNECT, ACCESS_FINE_LOCATION on older Android)
 * from your Activity before using client APIs and BLUETOOTH_ADVERTISE for advertising on some devices.
 */
class DualRoleBleManager(private val context: Context) {
    private val TAG = "DualRoleBleManager"

    // --- UUIDs (example UUIDs) ---
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000feed-0000-1000-8000-00805f9b34fb")
        val RX_CHAR_UUID: UUID = UUID.fromString("0000beef-0000-1000-8000-00805f9b34fb") // client -> server (WRITE)
        val TX_CHAR_UUID: UUID = UUID.fromString("0000cafe-0000-1000-8000-00805f9b34fb") // server -> client (NOTIFY)
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothManager: BluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    // Peripheral/server side
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private val connectedDevices = mutableSetOf<BluetoothDevice>()

    // Client side
//    private var bluetoothLeScanner: BluetoothLeScanner? = null
//    private var scanning = false
    private var bluetoothGatt: BluetoothGatt? = null

    // Local references for server characteristics so the server can send notifications
    private var serverTxCharacteristic: BluetoothGattCharacteristic? = null
    private var serverRxCharacteristic: BluetoothGattCharacteristic? = null

//    init {
//        bluetoothLeAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
//        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
//    }

    // ------------------ GATT SERVER (Peripheral) ------------------

    @SuppressLint("MissingPermission")
    fun startGattServer() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth not available or disabled")
            return
        }

        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        if (gattServer == null) {
            Log.e(TAG, "Unable to create GATT server")
            return
        }

        // Create service
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // RX characteristic (WRITE): client writes to this char to send data to the server
        val rxChar = BluetoothGattCharacteristic(
            RX_CHAR_UUID,
            // allow write without response & write
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        // TX characteristic (NOTIFY): server sends notifications to clients
        val txChar = BluetoothGattCharacteristic(
            TX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ // permission on server side; client uses enable notification
        )

        // CCCD descriptor for notifications
        val cccd = BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)
        txChar.addDescriptor(cccd)

        service.addCharacteristic(rxChar)
        service.addCharacteristic(txChar)

        val added = gattServer!!.addService(service)
        Log.i(TAG, "GATT service added: $added")

        // keep local refs
        serverRxCharacteristic = rxChar
        serverTxCharacteristic = txChar
    }

    @SuppressLint("MissingPermission")
    fun stopGattServer() {
        gattServer?.close()
        gattServer = null
        connectedDevices.clear()
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            device ?: return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedDevices.add(device)
                Log.i(TAG, "Server: device connected: ${device.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedDevices.remove(device)
                Log.i(TAG, "Server: device disconnected: ${device.address}")
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicReadRequest(device: BluetoothDevice?, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic?) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
            device ?: return
            characteristic ?: return
            // This server does not expect reads for our example, but respond gracefully
            val value = characteristic.value ?: byteArrayOf()
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)
            device ?: return
            characteristic ?: return

            if (characteristic.uuid == RX_CHAR_UUID) {
                val text = value?.toString(Charset.forName("UTF-8")) ?: ""
                Log.i(TAG, "Server received from client(${device.address}): $text")

                // You can react to incoming message here (e.g., update UI via a callback)

                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            } else {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value)
            device ?: return
            descriptor ?: return

            if (descriptor.uuid == CCCD_UUID) {
                val enabled = Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                Log.i(TAG, "Server descriptor write: enable notifications=$enabled from ${device.address}")
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            } else {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun notifyConnectedClients(text: String) {
        val bytes = text.toByteArray(Charset.forName("UTF-8"))
        val char = serverTxCharacteristic ?: run {
            Log.w(TAG, "serverTxCharacteristic is null")
            return
        }
        char.value = bytes

        // Notify each connected device
        connectedDevices.forEach { device ->
            val success = gattServer?.notifyCharacteristicChanged(device, char, false) ?: false
            Log.i(TAG, "notify to ${device.address} success=$success")
        }
    }

    // ------------------ ADVERTISING (Peripheral) ------------------

//    @SuppressLint("MissingPermission")
//    fun startAdvertising() {
//        if (bluetoothLeAdvertiser == null) {
//            Log.w(TAG, "Device does not support BLE advertising")
//            return
//        }
//
//        val settings = AdvertiseSettings.Builder()
//            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
//            .setConnectable(true)
//            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
//            .build()
//
//        val data = AdvertiseData.Builder()
//            .setIncludeDeviceName(true)
//            .addServiceUuid(ParcelUuid(SERVICE_UUID))
//            .build()
//
//        val scanResp = AdvertiseData.Builder()
//            .addServiceUuid(ParcelUuid(SERVICE_UUID))
//            .build()
//
//        bluetoothLeAdvertiser?.startAdvertising(settings, data, scanResp, advertiseCallback)
//        Log.i(TAG, "Started advertising service $SERVICE_UUID")
//    }
//
//    @SuppressLint("MissingPermission")
//    fun stopAdvertising() {
//        bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
//    }
//
//    private val advertiseCallback = object : AdvertiseCallback() {
//        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
//            super.onStartSuccess(settingsInEffect)
//            Log.i(TAG, "Advertise started")
//        }
//
//        override fun onStartFailure(errorCode: Int) {
//            super.onStartFailure(errorCode)
//            Log.e(TAG, "Advertise failed: $errorCode")
//        }
//    }

    // ------------------ GATT CLIENT (Central) ------------------

//    // Scanning
//    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
//    fun startScan() {
//        if (bluetoothLeScanner == null) return
//        if (scanning) return
//
//        val filter = ScanFilter.Builder()
//            .setServiceUuid(ParcelUuid(SERVICE_UUID))
//            .build()
//        val settings = ScanSettings.Builder()
//            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
//            .build()
//
//        bluetoothLeScanner?.startScan(listOf(filter), settings, scanCallback)
//        scanning = true
//        Log.i(TAG, "Started scanning for service $SERVICE_UUID")
//    }
//
//    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN])
//    fun stopScan() {
//        if (bluetoothLeScanner == null) return
//        if (!scanning) return
//        bluetoothLeScanner?.stopScan(scanCallback)
//        scanning = false
//    }
//
//    private val scanCallback = object : ScanCallback() {
//        @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
//        override fun onScanResult(callbackType: Int, result: ScanResult?) {
//            super.onScanResult(callbackType, result)
//            result ?: return
//            val device = result.device
//            Log.i(TAG, "Scan found ${device.address} name=${device.name}")
//
//            // Auto connect policy: connect if we don't have a connection already
//            if (bluetoothGatt == null) {
//                connectToDevice(device)
//                stopScan()
//            }
//        }
//
//        override fun onScanFailed(errorCode: Int) {
//            super.onScanFailed(errorCode)
//            Log.e(TAG, "Scan failed: $errorCode")
//        }
//    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        Log.i(TAG, "Connecting to device: ${device.address}")
        // false -> not autoConnect
        bluetoothGatt = device.connectGatt(context, false, gattClientCallback)
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Client connected -> discoverServices")
                // request MTU (best-effort)
                try {
                    gatt.requestMtu(247)
                } catch (e: Exception) {
                    Log.w(TAG, "requestMtu failed: ${e.message}")
                }
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Client disconnected")
                bluetoothGatt?.close()
                bluetoothGatt = null
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            Log.i(TAG, "MTU changed: $mtu status=$status")
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val svc = gatt.getService(SERVICE_UUID) ?: return
            val rx = svc.getCharacteristic(RX_CHAR_UUID) // client writes to this
            val tx = svc.getCharacteristic(TX_CHAR_UUID) // client enables notifications on this

            if (tx != null) {
                gatt.setCharacteristicNotification(tx, true)
                val desc = tx.getDescriptor(CCCD_UUID)
                desc?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                desc?.let { gatt.writeDescriptor(it) }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            super.onCharacteristicChanged(gatt, characteristic)
            if (characteristic.uuid == TX_CHAR_UUID) {
                val msg = characteristic.value?.toString(Charset.forName("UTF-8")) ?: ""
                Log.i(TAG, "Client received notification: $msg")
                // deliver to UI via callback
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            Log.i(TAG, "Client write status=$status for ${characteristic.uuid}")
        }
    }

    @SuppressLint("MissingPermission")
    fun clientWrite(message: String) {
        val gatt = bluetoothGatt ?: run {
            Log.w(TAG, "No client GATT connection")
            return
        }
        val svc = gatt.getService(SERVICE_UUID) ?: run {
            Log.w(TAG, "Service not found on remote device")
            return
        }
        val rx = svc.getCharacteristic(RX_CHAR_UUID) ?: run {
            Log.w(TAG, "RX characteristic not found on remote device")
            return
        }

        rx.value = message.toByteArray(Charset.forName("UTF-8"))
        // use WRITE_NO_RESPONSE for higher throughput when supported
        val ok = gatt.writeCharacteristic(rx)
        Log.i(TAG, "client write initiated ok=$ok message=$message")
    }

    // ------------------ CLEANUP ------------------

    @SuppressLint("MissingPermission")
    fun shutdown() {
        bluetoothGatt?.close()
        bluetoothGatt = null
        stopGattServer()
    }
}
