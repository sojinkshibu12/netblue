package com.example.meshtalk

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.example.dualroleble.DualRoleBleManager
//import com.example.meshtalk.R


@SuppressLint("MissingPermission")
class ChatActivity : AppCompatActivity() {

    private lateinit var listMessages: ListView
    private lateinit var etMessage: EditText
    private lateinit var tvChatStatus: TextView
    private lateinit var btnSend: Button

    private lateinit var messageAdapter: ArrayAdapter<String>
    private lateinit var dualRole: DualRoleBleManager
    private lateinit var bluetoothAdapter: BluetoothAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        // UI
        listMessages = findViewById(R.id.listMessages)
        etMessage = findViewById(R.id.etMessage)
        tvChatStatus = findViewById(R.id.tvChatStatus)
        btnSend = findViewById(R.id.btnSend)

        messageAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        listMessages.adapter = messageAdapter

        // Bluetooth
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // BLE Manager
        dualRole = DualRoleBleManager(
            context = this,

        )
        dualRole.onMessageReceived = { msg ->
            runOnUiThread {
                addMessage("Friend: $msg")
            }
        }

        val deviceAddress = intent.getStringExtra("device_address")

//        if (deviceAddress == null) {
//            // 🟢 SERVER MODE (advertise + GATT server)
//            tvChatStatus.text = "Waiting for connection..."
//            dualRole.startServer()
//        } else {
//            // 🔵 CLIENT MODE (connect as GATT client)
//            val device = bluetoothAdapter.getRemoteDevice(deviceAddress)
//            tvChatStatus.text = "Connecting..."
//            dualRole.connectToDevice(device)
//        }

        btnSend.setOnClickListener {
            val msg = etMessage.text.toString()
            if (msg.isNotBlank()) {
                dualRole.clientWrite(msg)
                addMessage("You: $msg")
                etMessage.text.clear()
            }
        }
    }

    private fun addMessage(message: String) {
        messageAdapter.add(message)
        listMessages.smoothScrollToPosition(messageAdapter.count - 1)
    }

    override fun onDestroy() {
        super.onDestroy()

    }
}
