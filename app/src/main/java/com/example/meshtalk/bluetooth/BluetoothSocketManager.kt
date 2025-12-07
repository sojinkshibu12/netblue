package com.example.meshtalk.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*
import kotlin.concurrent.thread

@SuppressLint("MissingPermission")
class BluetoothSocketManager(
    private val adapter: BluetoothAdapter,
    private val onMessageReceived: (String) -> Unit,
    private val onStatusUpdate: (String) -> Unit
) {
    private var serverSocket: BluetoothServerSocket? = null
    private var connectedSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private val APP_NAME = "MESHTalk"
    // Using a unique, consistent UUID is critical for a reliable connection
    private val APP_UUID: UUID = UUID.fromString("f9b76a90-48e2-4503-883c-b715a31a58b8")

    fun startServer() {
        thread {
            try {
                serverSocket = adapter.listenUsingRfcommWithServiceRecord(APP_NAME, APP_UUID)
                onStatusUpdate("Listening for connections...")
                val socket = serverSocket?.accept() // This is a blocking call
                socket?.let {
                    manageConnection(it)
                    onStatusUpdate("Device connected: ${it.remoteDevice.name}")
                }
            } catch (e: IOException) {
                // This error is expected when the socket is closed, so only log if it's not a 'Socket closed' error.
                if (serverSocket?.toString()?.contains("Socket closed") == false) {
                    onStatusUpdate("Server error: ${e.message}")
                    Log.e("BluetoothSocketManager", "Server failed", e)
                }
            }
        }
    }

    fun connectToDevice(device: BluetoothDevice) {
        thread {
            try {
                onStatusUpdate("Connecting to ${device.name}...")
                val socket = device.createRfcommSocketToServiceRecord(APP_UUID)

                // As per Android documentation, discovery must be cancelled before connecting.
                if (adapter.isDiscovering) {
                    adapter.cancelDiscovery()
                }

                socket.connect()
                manageConnection(socket)
                onStatusUpdate("Connected to ${device.name}")
            } catch (e: IOException) {
                onStatusUpdate("Connection failed: ${e.message}")
                Log.e("BluetoothSocketManager", "Connection failed", e)
            }
        }
    }

    private fun manageConnection(socket: BluetoothSocket) {
        connectedSocket = socket
        inputStream = socket.inputStream
        outputStream = socket.outputStream
        thread { readFromStream() }
    }

    private fun readFromStream() {
        val buffer = ByteArray(1024)
        var bytes: Int
        while (true) {
            try {
                bytes = inputStream?.read(buffer) ?: break
                if (bytes > 0) {
                    val message = String(buffer, 0, bytes)
                    onMessageReceived(message)
                }
            } catch (e: IOException) {
                onStatusUpdate("Connection lost: ${e.message}")
                break
            }
        }
    }

    fun sendMessage(message: String) {
        thread {
            try {
                outputStream?.write(message.toByteArray())
            } catch (e: IOException) {
                onStatusUpdate("Send failed: ${e.message}")
            }
        }
    }

    fun close() {
        try {
            connectedSocket?.close()
            serverSocket?.close()
            inputStream?.close()
            outputStream?.close()
        } catch (e: IOException) {
            Log.e("BluetoothSocketManager", "Close failed", e)
        }
    }
}