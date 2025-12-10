package com.example.dualroleble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import java.nio.charset.Charset
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.ArrayDeque

/**
 * DualRoleBleManager
 * - Single-file dual-role BLE manager (GATT Server + simple GATT Client)
 * - Improvements added:
 *   * Client-side write queue with MTU-aware chunking
 *   * Reliable writes (WRITE_TYPE_DEFAULT) by default and queue processing on onCharacteristicWrite
 *   * Store discovered remote RX characteristic to avoid repeated lookups
 *   * Track current MTU and use it for chunk sizing
 *   * Fix pending reply queue usage and make notify behavior deterministic
 *   * Minor safety and logging improvements
 */
class DualRoleBleManager(private val context: Context) {
    private val TAG = "DualRoleBleManager"

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000abcd-0000-1000-8000-00805f9b34fb")
        val RX_CHAR_UUID: UUID = UUID.fromString("0000beef-0000-1000-8000-00805f9b34fb")
        val TX_CHAR_UUID: UUID = UUID.fromString("0000cafe-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb") }

    private val bluetoothManager: BluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var gattServer: BluetoothGattServer? = null

    // track which devices enabled notifications (store device.address)
    private val notifyEnabledDevices = Collections.synchronizedSet(mutableSetOf<String>())
    // make connectedDevices synchronized
    private val connectedDevices = Collections.synchronizedSet(mutableSetOf<BluetoothDevice>())

    // map of pending replies per device (if device hasn't enabled notifications yet)
    private val pendingReplies = ConcurrentHashMap<String, ArrayDeque<String>>()

    // Client side
    private var bluetoothGatt: BluetoothGatt? = null

    /**
     * Optional callback invoked when the manager establishes a client (central) connection.
     * MainActivity can set this to be notified and then call clientWrite(...) safely.
     */
    var onClientConnected: ((BluetoothDevice) -> Unit)? = null

    // cache of remote RX characteristic
    private var remoteRxCharacteristic: BluetoothGattCharacteristic? = null // cached after services discovered


    // Local references for server characteristics so the server can send notifications
    private var serverTxCharacteristic: BluetoothGattCharacteristic? = null
    private var serverRxCharacteristic: BluetoothGattCharacteristic? = null

    // ------------------ CLIENT WRITE QUEUE / MTU ------------------
    private val writeQueue: ArrayDeque<Pair<BluetoothGattCharacteristic, ByteArray>> = ArrayDeque()
    @Volatile
    private var writeInProgress = false
    @Volatile
    private var currentMtu: Int = 23 // default until onMtuChanged

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
        } else {
            Log.d(TAG, "created the GATT server")
        }

        // Create service
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // RX characteristic (WRITE): client writes to this char to send data to the server
        val rxChar = BluetoothGattCharacteristic(
            RX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        // TX characteristic (NOTIFY): server sends notifications to clients
        val txChar = BluetoothGattCharacteristic(
            TX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
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
        notifyEnabledDevices.clear()
        pendingReplies.clear()
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            Log.i(TAG, "onConnectionStateChange device=${device?.address} status=$status newState=$newState")
            device ?: return

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedDevices.add(device)
                Log.i(TAG, "Server: device connected: ${device.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedDevices.remove(device)
                notifyEnabledDevices.remove(device.address)
                Log.i(TAG, "Server: device disconnected: ${device.address}")
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicReadRequest(device: BluetoothDevice?, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic?) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
            device ?: return
            characteristic ?: return
            val value = characteristic.value ?: byteArrayOf()
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
            Log.i(TAG, "onCharacteristicReadRequest device=${device.address} char=${characteristic.uuid}")
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
            Log.d(TAG,"device : ${device} char : ${characteristic?.uuid === RX_CHAR_UUID}")
            device ?: return
            characteristic ?: return

            Log.i(TAG, "CHAR WRITE from=${device.address} charUuid=${characteristic.uuid} len=${value?.size ?: 0}")
            val bytes = value ?: byteArrayOf()
            Log.i(TAG, "CHAR Received bytes (hex): ${bytes.joinToString(" ") { "%02X".format(it) }}")
            Log.i(TAG, "notifyEnabledDevices contains ${device.address}? ${notifyEnabledDevices.contains(device.address)}")
            Log.i(TAG, "connectedDevices contains ${device.address}? ${connectedDevices.any { it.address == device.address }}")

            if (offset != 0) {
                if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                return
            }
            if (preparedWrite) {
                if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                return
            }

            if (characteristic.uuid == RX_CHAR_UUID) {
                val text = try { String(bytes, Charsets.UTF_8) } catch (e: Exception) { bytes.joinToString(" ") { "%02X".format(it) } }
                Log.i(TAG, "Server received from client(${device.address}): $text")

                // Prefer notifying the specific device that sent the message
                // send text as-is (don't add duplicate "Echo:" twice)
                notifyDevice(device, "Echo: $text")

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

            Log.i(TAG, "DESCRIPTOR WRITE from=${device.address} descUuid=${descriptor.uuid} value=${value?.joinToString(" ") { "%02X".format(it) }}")

            if (descriptor.uuid == CCCD_UUID) {
                // persist the written value on the descriptor object
                descriptor.value = value

                val enabled = Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                if (enabled) notifyEnabledDevices.add(device.address) else notifyEnabledDevices.remove(device.address)

                Log.i(TAG, "Server descriptor write: enable notifications=$enabled from ${device.address}")

                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }

                // If we have queued replies for this device, send them now
                if (enabled) {
                    val q = pendingReplies.remove(device.address)
                    q?.forEach { msg ->
                        notifyDevice(device, msg)
                    }
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
        Log.d(TAG, "notifyConnectedClients: $text")
        val bytes = text.toByteArray(Charsets.UTF_8)
        val char = serverTxCharacteristic ?: run {
            Log.w(TAG, "serverTxCharacteristic is null")
            return
        }
        char.value = bytes

        connectedDevices.forEach { device ->
            if (!notifyEnabledDevices.contains(device.address)) {
                Log.d(TAG, "Skipping notify: ${device.address} has not enabled notifications; queueing")
                pendingReplies.getOrPut(device.address) { ArrayDeque() }.add(text)
                return@forEach
            }
            val success = gattServer?.notifyCharacteristicChanged(device, char, false) ?: false
            Log.i(TAG, "notify to ${device.address} success=$success")
        }
    }

    @SuppressLint("MissingPermission")
    fun notifyDevice(device: BluetoothDevice, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val char = serverTxCharacteristic ?: run {
            Log.w(TAG, "serverTxCharacteristic is null")
            return
        }
        char.value = bytes

        // ensure device enabled notifications
        if (!notifyEnabledDevices.contains(device.address)) {
            Log.d(TAG, "Not notifying ${device.address}: notifications not enabled; queueing reply")
            pendingReplies.getOrPut(device.address) { ArrayDeque() }.add(text)
            return
        }

        val success = gattServer?.notifyCharacteristicChanged(device, char, false) ?: false
        Log.i(TAG, "notify to ${device.address} queued success=$success")
    }

    // ------------------ CLIENT (Central) ------------------

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        Log.i(TAG, "Connecting to device: ${device.address}")
        // store/replace previous connection
        bluetoothGatt?.close()
        bluetoothGatt = device.connectGatt(context, false, gattClientCallback)
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Client connected -> discoverServices")
                // notify listener that client connected (UI can now safely call clientWrite)
                try {
                    onClientConnected?.invoke(gatt.device)
                } catch (e: Exception) {
                    Log.w(TAG, "onClientConnected handler threw: ${'$'}{e.message}")
                }
                // keep reference
                bluetoothGatt = gatt
                // try request high MTU (best-effort)
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
                remoteRxCharacteristic = null
                synchronized(writeQueue) { writeQueue.clear() }
                writeInProgress = false
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                currentMtu = mtu
                Log.i(TAG, "MTU changed: $mtu")
            } else {
                Log.w(TAG, "MTU change failed status=$status")
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Service discovery failed: status=$status")
                return
            }

            val svc = gatt.getService(SERVICE_UUID)
            if (svc == null) {
                Log.w(TAG, "Service $SERVICE_UUID not found")
                return
            }

            val rx = svc.getCharacteristic(RX_CHAR_UUID)
            val tx = svc.getCharacteristic(TX_CHAR_UUID)

            if (tx == null) {
                Log.w(TAG, "TX characteristic $TX_CHAR_UUID not found")
                return
            }

            // cache rx char for subsequent writes
            if (rx != null) remoteRxCharacteristic = rx

            // Enable local notifications (app side)
            val localSet = gatt.setCharacteristicNotification(tx, true)
            Log.d(TAG, "setCharacteristicNotification returned: $localSet for ${tx.uuid}")

            // Enable remote notifications by writing CCCD
            val cccd = tx.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                Log.w(TAG, "CCCD descriptor $CCCD_UUID not found on TX char")
                return
            }

            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val writeStarted = gatt.writeDescriptor(cccd)
            if (!writeStarted) {
                Log.w(TAG, "writeDescriptor returned false (failed to start)")
            } else {
                Log.d(TAG, "writeDescriptor started for CCCD")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            super.onDescriptorWrite(gatt, descriptor, status)
            if (descriptor.uuid == CCCD_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Successfully wrote CCCD; notifications enabled remotely.")
                } else {
                    Log.w(TAG, "Failed to write CCCD: status=$status")
                }
            } else {
                Log.d(TAG, "Descriptor written: ${descriptor.uuid}, status=$status")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            super.onCharacteristicChanged(gatt, characteristic)
            if (characteristic.uuid == TX_CHAR_UUID) {
                val payload = characteristic.value
                if (payload == null || payload.isEmpty()) {
                    Log.i(TAG, "Received empty payload on ${characteristic.uuid}")
                    return
                }
                // Proper conversion from bytes -> UTF-8 string
                val msg = try {
                    String(payload, Charsets.UTF_8)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to decode payload as UTF-8", e)
                    // fallback: hex
                    payload.joinToString(separator = " ") { "%02x".format(it) }
                }
                Log.i(TAG, "Client received notification: $msg")
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            Log.d(TAG, "onCharacteristicWrite char=${characteristic.uuid} status=$status")
            writeInProgress = false
            // process next chunk if available
            processNextWrite()

            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Characteristic ${characteristic.uuid} write succeeded")
            } else {
                Log.w(TAG, "Characteristic ${characteristic.uuid} write failed: status=$status")
            }
        }

    }

    @SuppressLint("MissingPermission")
    fun clientWrite(message: String) {
        val gatt = bluetoothGatt ?: run {
            Log.w(TAG, "No client GATT connection")
            return
        }

        val rx = remoteRxCharacteristic ?: run {

            val svc = gatt.getService(SERVICE_UUID)
            Log.w(TAG, "svc  : ${svc}")
            val found = svc?.getCharacteristic(RX_CHAR_UUID)
            if (found == null) {
                Log.w(TAG, "RX characteristic not found on remote device")
                return
            } else {
                remoteRxCharacteristic = found
            }
            remoteRxCharacteristic!!
        }

        val bytes = message.toByteArray(Charsets.UTF_8)
        Log.i(TAG, "client write bytes (hex): ${bytes.joinToString(" ") { "%02X".format(it) }}")

        // Chunk by MTU-3
        val chunkSize = if (currentMtu > 3) currentMtu - 3 else 20
        synchronized(writeQueue) {
            if (bytes.size <= chunkSize) {
                writeQueue.addLast(rx to bytes)
            } else {
                var offset = 0
                while (offset < bytes.size) {
                    val end = minOf(bytes.size, offset + chunkSize)
                    val slice = bytes.sliceArray(offset until end)
                    writeQueue.addLast(rx to slice)
                    offset = end
                }
            }
        }
        processNextWrite()
    }

    @androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    private fun processNextWrite() {
        val gatt = bluetoothGatt ?: run {
            writeInProgress = false
            return
        }
        synchronized(writeQueue)  {
            if (writeInProgress) return
            val next = if (writeQueue.isEmpty()) null else writeQueue.removeFirst()
            if (next == null) return
            val (char, bytes) = next
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT // request response for reliability
            char.value = bytes
            writeInProgress = true
            val started = gatt.writeCharacteristic(char)
            if (!started) {
                Log.w(TAG, "writeCharacteristic failed to start")
                writeInProgress = false
                // If failed to start, try next after brief fallback
                processNextWrite()
            } else {
                Log.d(TAG, "writeCharacteristic started len=${bytes.size}")
            }
        }
    }

    // ------------------ CLEANUP ------------------

    @SuppressLint("MissingPermission")
    fun shutdown() {
        bluetoothGatt?.close()
        bluetoothGatt = null
        stopGattServer()
    }
}
