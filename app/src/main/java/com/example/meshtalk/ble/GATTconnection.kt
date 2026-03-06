package com.example.dualroleble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresPermission
import com.google.gson.Gson
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.collections.ArrayDeque

private fun generateEcKeyPair(): KeyPair {
    val generator = KeyPairGenerator.getInstance("EC")
    generator.initialize(ECGenParameterSpec("secp256r1"))
    return generator.generateKeyPair()
}

data class MeshPacket(
    val packetId: String,
    val type: String,
    val senderId: String,
    val targetId: String,
    val ttl: Int,
    val publicKey: String? = null,
    val iv: String? = null,
    val ciphertext: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

private data class PendingMessage(
    val targetId: String,
    val text: String,
    val ttl: Int
)

class DualRoleBleManager(private val context: Context) {
    private val TAG = "DualRoleBleManager"
    private val gson = Gson()
    private val random = SecureRandom()

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000abcd-0000-1000-8000-00805f9b34fb")
        val RX_CHAR_UUID: UUID = UUID.fromString("0000beef-0000-1000-8000-00805f9b34fb")
        val TX_CHAR_UUID: UUID = UUID.fromString("0000cafe-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val DEFAULT_MTU = 23
        private const val ATT_OVERHEAD = 3
        private const val DEFAULT_TTL = 5
        private const val MAX_SEEN_IDS = 2000

        private const val TYPE_KEY_EXCHANGE = "KEY_EXCHANGE"
        private const val TYPE_KEY_EXCHANGE_RESPONSE = "KEY_EXCHANGE_RESPONSE"
        private const val TYPE_MESSAGE = "MESSAGE"
    }

    var onMessageReceived: ((String) -> Unit)? = null
    var onMessageReceivedFrom: ((String, String) -> Unit)? = null
    var onClientConnected: ((BluetoothDevice) -> Unit)? = null

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val nodeId: String by lazy {
        val adapterAddress = try {
            bluetoothAdapter?.address
        } catch (_: Exception) {
            null
        }
        if (!adapterAddress.isNullOrBlank() && adapterAddress != "02:00:00:00:00:00") {
            adapterAddress
        } else {
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: UUID.randomUUID().toString()
            "node-$androidId"
        }
    }

    private lateinit var myPrivateKey: PrivateKey
    private lateinit var myPublicKey: PublicKey

    private val sessionKeys = ConcurrentHashMap<String, SecretKeySpec>()
    private val pendingMessages = ConcurrentHashMap<String, MutableList<PendingMessage>>()
    private val hopToNodeId = ConcurrentHashMap<String, String>()
    private val nodeToHopId = ConcurrentHashMap<String, String>()

    private val seenPacketIds = LinkedHashSet<String>()
    private val seenPacketQueue = ArrayDeque<String>()

    private val receiveBuffers = ConcurrentHashMap<String, ByteArrayOutputStream>()

    private var gattServer: BluetoothGattServer? = null
    private var bluetoothGatt: BluetoothGatt? = null

    private val connectedDevices = Collections.synchronizedSet(mutableSetOf<BluetoothDevice>())
    private val notifyEnabledDevices = Collections.synchronizedSet(mutableSetOf<String>())
    private val pendingReplies = ConcurrentHashMap<String, ArrayDeque<String>>()
    private val deviceMtu = ConcurrentHashMap<String, Int>()
    private val notifyChunkQueues = ConcurrentHashMap<String, ArrayDeque<ByteArray>>()
    private val notifyInProgress = Collections.synchronizedSet(mutableSetOf<String>())
    private val notifyTimeoutTasks = ConcurrentHashMap<String, Runnable>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var serverTxCharacteristic: BluetoothGattCharacteristic? = null
    private var serverRxCharacteristic: BluetoothGattCharacteristic? = null
    private var remoteRxCharacteristic: BluetoothGattCharacteristic? = null

    private val writeQueue: ArrayDeque<Pair<BluetoothGattCharacteristic, ByteArray>> = ArrayDeque()

    @Volatile
    private var writeInProgress = false

    @Volatile
    private var currentMtu: Int = DEFAULT_MTU

    fun initKeys() {
        val keyPair = generateEcKeyPair()
        myPrivateKey = keyPair.private
        myPublicKey = keyPair.public
        Log.i(TAG, "ECDH key pair initialized for node=$nodeId")
    }

    private fun ensureKeys() {
        if (!::myPrivateKey.isInitialized || !::myPublicKey.isInitialized) {
            initKeys()
        }
    }

    private fun encodeMessage(data: ByteArray): ByteArray {
        val length = data.size
        val header = byteArrayOf(
            (length shr 24).toByte(),
            (length shr 16).toByte(),
            (length shr 8).toByte(),
            length.toByte()
        )
        return header + data
    }

    private fun processIncomingChunk(chunk: ByteArray, sourceHopId: String) {
        val stream = receiveBuffers.getOrPut(sourceHopId) { ByteArrayOutputStream() }
        stream.write(chunk)

        val data = stream.toByteArray()
        var offset = 0

        while (data.size - offset >= 4) {
            val len = ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)

            if (len < 0) {
                stream.reset()
                return
            }
            if (data.size - offset - 4 < len) break

            val payload = data.copyOfRange(offset + 4, offset + 4 + len)
            handleIncomingPayload(payload, sourceHopId)
            offset += 4 + len
        }

        stream.reset()
        if (offset < data.size) {
            stream.write(data, offset, data.size - offset)
        }
    }

    private fun handleIncomingPayload(payload: ByteArray, sourceHopId: String) {
        val text = try {
            String(payload, Charsets.UTF_8).trim()
        } catch (e: Exception) {
            Log.w(TAG, "Invalid UTF-8 payload", e)
            return
        }

        if (!text.startsWith("{")) {
            onMessageReceived?.invoke(text)
            onMessageReceivedFrom?.invoke(sourceHopId, text)
            return
        }

        val packet = try {
            gson.fromJson(text, MeshPacket::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Malformed mesh packet", e)
            return
        }

        handleMeshPacket(packet, sourceHopId)
    }

    private fun markSeen(packetId: String): Boolean {
        synchronized(seenPacketIds) {
            if (seenPacketIds.contains(packetId)) return false
            seenPacketIds.add(packetId)
            seenPacketQueue.addLast(packetId)

            while (seenPacketQueue.size > MAX_SEEN_IDS) {
                val oldest = seenPacketQueue.removeFirst()
                seenPacketIds.remove(oldest)
            }
            return true
        }
    }

    private fun makePacketId(): String = "$nodeId-${UUID.randomUUID()}"

    private fun myPublicKeyBase64(): String {
        ensureKeys()
        return Base64.encodeToString(myPublicKey.encoded, Base64.NO_WRAP)
    }

    private fun decodePublicKey(base64: String): PublicKey {
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        val keySpec = X509EncodedKeySpec(bytes)
        return KeyFactory.getInstance("EC").generatePublic(keySpec)
    }

    private fun deriveSessionKey(peerId: String, peerPublicKeyBase64: String) {
        ensureKeys()
        val peerPublicKey = decodePublicKey(peerPublicKeyBase64)

        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(myPrivateKey)
        agreement.doPhase(peerPublicKey, true)
        val sharedSecret = agreement.generateSecret()

        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(sharedSecret)
        val keyBytes = digest.digest()

        sessionKeys[peerId] = SecretKeySpec(keyBytes, "AES")
        Log.i(TAG, "Session key established with peer=$peerId")
    }

    private fun encryptMessage(targetId: String, plainText: String): Pair<String, String>? {
        val key = sessionKeys[targetId] ?: return null

        val iv = ByteArray(12)
        random.nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charset.forName("UTF-8")))

        return Base64.encodeToString(iv, Base64.NO_WRAP) to
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decryptMessage(senderId: String, ivBase64: String, cipherBase64: String): String? {
        val key = sessionKeys[senderId] ?: return null

        val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
        val cipherText = Base64.decode(cipherBase64, Base64.NO_WRAP)

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            val plainBytes = cipher.doFinal(cipherText)
            String(plainBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "AES-GCM decrypt failed for sender=$senderId", e)
            null
        }
    }

    private fun enqueuePendingMessage(targetId: String, text: String, ttl: Int) {
        val list = pendingMessages.getOrPut(targetId) { mutableListOf() }
        list.add(PendingMessage(targetId, text, ttl))
    }

    private fun flushPendingMessages(targetId: String) {
        val queued = pendingMessages.remove(targetId) ?: return
        queued.forEach { pending ->
            sendMessageTo(pending.targetId, pending.text, pending.ttl)
        }
    }

    private fun rememberPeerIdentity(hopId: String, nodeId: String) {
        hopToNodeId[hopId] = nodeId
        nodeToHopId[nodeId] = hopId

        if (hopId != nodeId) {
            val moved = pendingMessages.remove(hopId)
            if (!moved.isNullOrEmpty()) {
                val bucket = pendingMessages.getOrPut(nodeId) { mutableListOf() }
                moved.forEach { pending -> bucket.add(pending.copy(targetId = nodeId)) }
            }
        }
    }

    private fun requestKeyExchange(targetId: String, ttl: Int = DEFAULT_TTL) {
        val packet = MeshPacket(
            packetId = makePacketId(),
            type = TYPE_KEY_EXCHANGE,
            senderId = nodeId,
            targetId = targetId,
            ttl = ttl,
            publicKey = myPublicKeyBase64()
        )
        routePacket(packet, excludeHop = null)
    }

    private fun routePacket(packet: MeshPacket, excludeHop: String?) {
        if (packet.ttl <= 0) return

        val json = gson.toJson(packet)

        val clientHop = bluetoothGatt?.device?.address
        if (!clientHop.isNullOrBlank() && clientHop != excludeHop) {
            clientWriteRaw(json)
        }

        val serverTargets = synchronized(connectedDevices) { connectedDevices.toList() }
        serverTargets.forEach { device ->
            if (device.address != excludeHop) {
                notifyDeviceJson(device, json)
            }
        }
    }

    private fun handleMeshPacket(packet: MeshPacket, sourceHopId: String) {
        rememberPeerIdentity(sourceHopId, packet.senderId)

        if (!markSeen(packet.packetId)) return
        if (packet.ttl <= 0) return

        when (packet.type) {
            TYPE_KEY_EXCHANGE -> {
                val isDirectPeer = isDirectHop(sourceHopId)
                if (packet.targetId == nodeId || isDirectPeer) {
                    val pub = packet.publicKey
                    if (pub.isNullOrBlank()) return

                    deriveSessionKey(packet.senderId, pub)

                    val response = MeshPacket(
                        packetId = makePacketId(),
                        type = TYPE_KEY_EXCHANGE_RESPONSE,
                        senderId = nodeId,
                        targetId = packet.senderId,
                        ttl = DEFAULT_TTL,
                        publicKey = myPublicKeyBase64()
                    )
                    routePacket(response, excludeHop = null)
                    flushPendingMessages(packet.senderId)
                } else {
                    routePacket(packet.copy(ttl = packet.ttl - 1), excludeHop = sourceHopId)
                }
            }

            TYPE_KEY_EXCHANGE_RESPONSE -> {
                val isDirectPeer = isDirectHop(sourceHopId)
                if (packet.targetId == nodeId || isDirectPeer) {
                    val pub = packet.publicKey
                    if (pub.isNullOrBlank()) return

                    deriveSessionKey(packet.senderId, pub)
                    flushPendingMessages(packet.senderId)
                } else {
                    routePacket(packet.copy(ttl = packet.ttl - 1), excludeHop = sourceHopId)
                }
            }

            TYPE_MESSAGE -> {
                val isDirectPeer = isDirectHop(sourceHopId)
                val canDecryptForSender = sessionKeys.containsKey(packet.senderId)
                if (packet.targetId == nodeId || (isDirectPeer && canDecryptForSender)) {
                    val iv = packet.iv
                    val cipher = packet.ciphertext
                    if (iv.isNullOrBlank() || cipher.isNullOrBlank()) return

                    val plain = decryptMessage(packet.senderId, iv, cipher)
                    if (plain != null) {
                        onMessageReceived?.invoke(plain)
                        onMessageReceivedFrom?.invoke(sourceHopId, plain)
                    } else {
                        requestKeyExchange(packet.senderId, packet.ttl.coerceAtLeast(2))
                    }
                } else {
                    routePacket(packet.copy(ttl = packet.ttl - 1), excludeHop = sourceHopId)
                }
            }

            else -> {
                Log.w(TAG, "Unknown packet type=${packet.type}")
            }
        }
    }

    private fun isDirectHop(sourceHopId: String): Boolean {
        val serverSide = synchronized(connectedDevices) { connectedDevices.any { it.address == sourceHopId } }
        val clientSide = bluetoothGatt?.device?.address == sourceHopId
        return serverSide || clientSide
    }

    // ------------------ GATT SERVER (Peripheral) ------------------

    @SuppressLint("MissingPermission")
    fun startGattServer() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth unavailable/disabled")
            return
        }

        ensureKeys()

        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        if (gattServer == null) {
            Log.e(TAG, "Unable to create GATT server")
            return
        }

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val rxChar = BluetoothGattCharacteristic(
            RX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val txChar = BluetoothGattCharacteristic(
            TX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val cccd = BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        txChar.addDescriptor(cccd)

        service.addCharacteristic(rxChar)
        service.addCharacteristic(txChar)

        val added = gattServer!!.addService(service)
        Log.i(TAG, "GATT service added=$added")

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
        deviceMtu.clear()
        notifyChunkQueues.clear()
        notifyInProgress.clear()
        notifyTimeoutTasks.values.forEach { mainHandler.removeCallbacks(it) }
        notifyTimeoutTasks.clear()
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            device ?: return

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedDevices.add(device)
                deviceMtu[device.address] = DEFAULT_MTU
                Log.i(TAG, "Server connected ${device.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedDevices.remove(device)
                notifyEnabledDevices.remove(device.address)
                deviceMtu.remove(device.address)
                notifyChunkQueues.remove(device.address)
                notifyInProgress.remove(device.address)
                notifyTimeoutTasks.remove(device.address)?.let { mainHandler.removeCallbacks(it) }
                Log.i(TAG, "Server disconnected ${device.address}")
            }
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            super.onMtuChanged(device, mtu)
            if (device != null && mtu > 0) {
                deviceMtu[device.address] = mtu
                Log.i(TAG, "Server MTU for ${device.address} changed to $mtu")
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
            device ?: return
            val value = characteristic?.value ?: byteArrayOf()
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
            super.onCharacteristicWriteRequest(
                device,
                requestId,
                characteristic,
                preparedWrite,
                responseNeeded,
                offset,
                value
            )

            device ?: return
            characteristic ?: return

            if (offset != 0 || preparedWrite) {
                if (responseNeeded) {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                        0,
                        null
                    )
                }
                return
            }

            if (characteristic.uuid == RX_CHAR_UUID) {
                val bytes = value ?: byteArrayOf()
                processIncomingChunk(bytes, device.address)
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            } else if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
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
            super.onDescriptorWriteRequest(
                device,
                requestId,
                descriptor,
                preparedWrite,
                responseNeeded,
                offset,
                value
            )

            device ?: return
            descriptor ?: return

            if (descriptor.uuid == CCCD_UUID) {
                descriptor.value = value
                val enabled = value != null && value.isNotEmpty() && (value[0].toInt() and 0x01) != 0

                if (enabled) notifyEnabledDevices.add(device.address)
                else notifyEnabledDevices.remove(device.address)

                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }

                if (enabled) {
                    val queued = pendingReplies.remove(device.address)
                    queued?.forEach { json -> notifyDeviceJson(device, json) }
                }
            } else if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
            super.onNotificationSent(device, status)
            device ?: return

            val address = device.address
            notifyInProgress.remove(address)
            notifyTimeoutTasks.remove(address)?.let { mainHandler.removeCallbacks(it) }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Notification failed for $address status=$status")
            }

            processNextNotifyChunk(device)
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyDeviceJson(device: BluetoothDevice, jsonPayload: String) {
        val txChar = serverTxCharacteristic ?: return

        if (!notifyEnabledDevices.contains(device.address)) {
            pendingReplies.getOrPut(device.address) { ArrayDeque() }.add(jsonPayload)
            return
        }

        val framed = encodeMessage(jsonPayload.toByteArray(Charsets.UTF_8))
        val mtu = deviceMtu[device.address] ?: DEFAULT_MTU
        val chunkSize = (mtu - ATT_OVERHEAD).coerceAtLeast(20)
        val queue = notifyChunkQueues.getOrPut(device.address) { ArrayDeque() }

        synchronized(queue) {
            var offset = 0
            while (offset < framed.size) {
                val end = minOf(framed.size, offset + chunkSize)
                queue.addLast(framed.copyOfRange(offset, end))
                offset = end
            }
        }

        processNextNotifyChunk(device)
    }

    @SuppressLint("MissingPermission")
    private fun processNextNotifyChunk(device: BluetoothDevice) {
        val txChar = serverTxCharacteristic ?: return
        val address = device.address

        if (!notifyEnabledDevices.contains(address)) return
        if (notifyInProgress.contains(address)) return

        val queue = notifyChunkQueues[address] ?: return
        val nextChunk = synchronized(queue) {
            if (queue.isEmpty()) null else queue.removeFirst()
        } ?: return

        txChar.value = nextChunk
        val started = gattServer?.notifyCharacteristicChanged(device, txChar, false) ?: false
        if (started) {
            notifyInProgress.add(address)
            val timeoutTask = Runnable {
                if (notifyInProgress.remove(address)) {
                    Log.w(TAG, "Notification callback timeout for $address, continuing send queue")
                    processNextNotifyChunk(device)
                }
            }
            notifyTimeoutTasks.put(address, timeoutTask)?.let { mainHandler.removeCallbacks(it) }
            mainHandler.postDelayed(timeoutTask, 150)
        } else {
            synchronized(queue) { queue.addFirst(nextChunk) }
            Log.w(TAG, "notifyCharacteristicChanged failed to start for $address")
        }
    }

    // ------------------ CLIENT (Central) ------------------

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        ensureKeys()
        Log.i(TAG, "Connecting to device=${device.address}")

        bluetoothGatt?.close()
        bluetoothGatt = device.connectGatt(context, false, gattClientCallback)
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    bluetoothGatt = gatt
                    val started = gatt.discoverServices()
                    Log.i(TAG, "Client connected, discoverServices started=$started")

                    try {
                        gatt.requestMtu(247)
                    } catch (e: Exception) {
                        Log.w(TAG, "requestMtu failed", e)
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Client disconnected")
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                    remoteRxCharacteristic = null

                    synchronized(writeQueue) { writeQueue.clear() }
                    writeInProgress = false
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                currentMtu = mtu
                Log.i(TAG, "Client MTU changed to $mtu")
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Service discovery failed status=$status")
                return
            }

            val service = gatt.getService(SERVICE_UUID) ?: return
            val rx = service.getCharacteristic(RX_CHAR_UUID)
            val tx = service.getCharacteristic(TX_CHAR_UUID) ?: return

            remoteRxCharacteristic = rx

            gatt.setCharacteristicNotification(tx, true)
            val cccd = tx.getDescriptor(CCCD_UUID) ?: return
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(cccd)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            super.onDescriptorWrite(gatt, descriptor, status)
            if (descriptor.uuid == CCCD_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                onClientConnected?.invoke(gatt.device)
                requestKeyExchange(gatt.device.address)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            super.onCharacteristicChanged(gatt, characteristic)
            if (characteristic.uuid == TX_CHAR_UUID) {
                val payload = characteristic.value ?: return
                processIncomingChunk(payload, gatt.device.address)
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            writeInProgress = false
            processNextWrite()

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Characteristic write failed status=$status")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enqueueClientFramedWrite(frame: ByteArray) {
        val gatt = bluetoothGatt ?: return
        val rx = remoteRxCharacteristic ?: run {
            val service = gatt.getService(SERVICE_UUID)
            val found = service?.getCharacteristic(RX_CHAR_UUID)
            if (found != null) remoteRxCharacteristic = found
            found
        } ?: return

        val chunkSize = (currentMtu - ATT_OVERHEAD).coerceAtLeast(20)

        synchronized(writeQueue) {
            var offset = 0
            while (offset < frame.size) {
                val end = minOf(frame.size, offset + chunkSize)
                writeQueue.addLast(rx to frame.copyOfRange(offset, end))
                offset = end
            }
        }

        processNextWrite()
    }

    @SuppressLint("MissingPermission")
    private fun clientWriteRaw(payload: String) {
        val framed = encodeMessage(payload.toByteArray(Charsets.UTF_8))
        enqueueClientFramedWrite(framed)
    }

    @SuppressLint("MissingPermission")
    fun clientWrite(message: String) {
        // Legacy raw text send for compatibility/testing.
        clientWriteRaw(message)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun processNextWrite() {
        val gatt = bluetoothGatt ?: run {
            writeInProgress = false
            return
        }

        synchronized(writeQueue) {
            if (writeInProgress || writeQueue.isEmpty()) return

            val (characteristic, bytes) = writeQueue.removeFirst()
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = bytes

            writeInProgress = true
            val started = gatt.writeCharacteristic(characteristic)
            if (!started) {
                writeInProgress = false
                processNextWrite()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun sendMessageTo(targetId: String, text: String, ttl: Int = DEFAULT_TTL) {
        ensureKeys()

        val resolvedTargetId = hopToNodeId[targetId] ?: targetId
        val effectiveTargetId = resolveSendTargetId(targetId, resolvedTargetId)
        Log.i(
            TAG,
            "sendMessageTo target=$targetId resolved=$resolvedTargetId effective=$effectiveTargetId " +
                "keys=${sessionKeys.keys.joinToString()}"
        )

        if (effectiveTargetId == nodeId || targetId == nodeId) {
            onMessageReceived?.invoke(text)
            onMessageReceivedFrom?.invoke(nodeId, text)
            return
        }

        val encrypted = encryptMessage(effectiveTargetId, text)
        if (encrypted == null) {
            Log.w(TAG, "No session key for $effectiveTargetId, queueing + requesting key exchange")
            enqueuePendingMessage(effectiveTargetId, text, ttl)
            requestKeyExchange(effectiveTargetId, ttl.coerceAtLeast(2))
            return
        }

        val packet = MeshPacket(
            packetId = makePacketId(),
            type = TYPE_MESSAGE,
            senderId = nodeId,
            targetId = effectiveTargetId,
            ttl = ttl,
            iv = encrypted.first,
            ciphertext = encrypted.second
        )

        routePacket(packet, excludeHop = null)
    }

    private fun resolveSendTargetId(originalTargetId: String, resolvedTargetId: String): String {
        if (sessionKeys.containsKey(resolvedTargetId)) return resolvedTargetId
        if (!isLikelyBleAddress(originalTargetId)) return resolvedTargetId

        val currentClientHop = bluetoothGatt?.device?.address
        if (!currentClientHop.isNullOrBlank()) {
            val viaCurrentHop = hopToNodeId[currentClientHop]
            if (!viaCurrentHop.isNullOrBlank() && sessionKeys.containsKey(viaCurrentHop)) {
                return viaCurrentHop
            }
        }

        val directServerCandidates = synchronized(connectedDevices) {
            connectedDevices.mapNotNull { hopToNodeId[it.address] }
                .filter { sessionKeys.containsKey(it) }
                .distinct()
        }
        if (directServerCandidates.size == 1) {
            return directServerCandidates.first()
        }

        // Some phones rotate BLE addresses (privacy address); if exactly one secure session
        // exists, use it for this chat target.
        if (sessionKeys.size == 1) {
            return sessionKeys.keys.first()
        }
        return resolvedTargetId
    }

    private fun isLikelyBleAddress(id: String): Boolean {
        return id.matches(Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$"))
    }

    fun isSenderForChat(chatId: String, senderId: String): Boolean {
        if (chatId == senderId) return true

        val chatNode = hopToNodeId[chatId] ?: chatId
        val senderNode = hopToNodeId[senderId] ?: senderId
        if (chatNode == senderNode) return true

        // If there is only one secure peer session, treat rotating MAC/hop IDs as same chat.
        if (isLikelyBleAddress(chatId) && sessionKeys.size == 1 && sessionKeys.containsKey(senderNode)) {
            return true
        }

        return false
    }

    @SuppressLint("MissingPermission")
    fun sendMessage(text: String, ttl: Long) {
        val target = bluetoothGatt?.device?.address
            ?: synchronized(connectedDevices) { connectedDevices.firstOrNull()?.address }

        if (target.isNullOrBlank()) {
            Log.w(TAG, "No peer available to send message")
            return
        }

        sendMessageTo(target, text, ttl.toInt().coerceAtLeast(1))
    }

    @SuppressLint("MissingPermission")
    fun shutdown() {
        bluetoothGatt?.close()
        bluetoothGatt = null
        stopGattServer()
    }
}
