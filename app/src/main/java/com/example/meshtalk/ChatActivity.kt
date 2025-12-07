package com.example.meshtalk

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.meshtalk.bluetooth.BluetoothSocketManager

@SuppressLint("MissingPermission")
class ChatActivity : AppCompatActivity() {

    private lateinit var listMessages: ListView
    private lateinit var etMessage: EditText
    private lateinit var tvChatStatus: TextView
    private lateinit var btManager: BluetoothSocketManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var messageAdapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        listMessages = findViewById(R.id.listMessages)
        etMessage = findViewById(R.id.etMessage)
        tvChatStatus = findViewById(R.id.tvChatStatus)
        val btnSend = findViewById<Button>(R.id.btnSend)

        messageAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        listMessages.adapter = messageAdapter

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        val deviceAddress = intent.getStringExtra("device_address")

        btManager = BluetoothSocketManager(
            adapter = bluetoothAdapter,
            onMessageReceived = { msg -> runOnUiThread { addMessage("Friend: $msg") } },
            onStatusUpdate = { status -> runOnUiThread { tvChatStatus.text = "Chat Status: $status" } }
        )

        if (deviceAddress == null) {
            // No address passed, so start in SERVER mode
            btManager.startServer()
        } else {
            // Address was passed, so start in CLIENT mode
            val device: BluetoothDevice = bluetoothAdapter.getRemoteDevice(deviceAddress)
            btManager.connectToDevice(device)
        }

        btnSend.setOnClickListener {
            val msg = etMessage.text.toString()
            if (msg.isNotEmpty()) {
                btManager.sendMessage(msg)
                addMessage("You: $msg")
                etMessage.text.clear()
            }
        }
    }

    private fun addMessage(message: String) {
        messageAdapter.add(message)
    }

    override fun onDestroy() {
        super.onDestroy()
        btManager.close()
    }
}