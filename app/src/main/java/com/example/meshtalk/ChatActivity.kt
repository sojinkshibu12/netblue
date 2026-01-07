package com.example.meshtalk

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.example.dualroleble.DualRoleBleManager

@SuppressLint("MissingPermission")
class ChatActivity : AppCompatActivity() {

    private lateinit var etMessage: EditText
    private lateinit var btnSend: Button
    private lateinit var chatContainer: LinearLayout
    private lateinit var chatScrollView: ScrollView

    private val dualRole: DualRoleBleManager = BleManagerHolder.dualRole

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        // ✅ Bind views
        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)
        chatContainer = findViewById(R.id.chatContainer)
        chatScrollView = findViewById(R.id.chatScrollView)

        // ✅ Receive BLE message
        dualRole.onMessageReceived = { msg ->
            runOnUiThread {
                addMessageBubble(" $msg", isMe = false)
            }
        }

        // ✅ Send message
        btnSend.setOnClickListener {
            val msg = etMessage.text.toString().trim()
            if (msg.isNotEmpty()) {
                dualRole.sendMessage(msg)
                addMessageBubble(" $msg", isMe = true)
                etMessage.text.clear()
            }
        }
    }

    // 🔥 CREATE ONE LINEARLAYOUT PER MESSAGE
    private fun addMessageBubble(text: String, isMe: Boolean) {

        val messageRow = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = if (isMe) Gravity.END else Gravity.START
            setPadding(8, 4, 8, 4)
        }

        val messageText = TextView(this).apply {
            this.text = text
            textSize = 16f
            maxWidth = 700
            setPadding(24, 12, 24, 12)
            setTextColor(Color.WHITE)
            setBackgroundColor(
                if (isMe)
                    Color.parseColor("#4CAF50")   // right (me)
                else
                    Color.parseColor("#607D8B")   // left (friend)
            )
        }

        messageRow.addView(messageText)
        chatContainer.addView(messageRow)

        // ✅ Auto scroll
        chatScrollView.post {
            chatScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }
}
