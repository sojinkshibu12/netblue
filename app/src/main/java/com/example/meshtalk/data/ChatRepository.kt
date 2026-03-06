package com.example.meshtalk.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ChatRepository(context: Context) {
    private val dao = AppDatabase.getInstance(context).chatMessageDao()
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun saveMessage(chatId: String, content: String, isOutgoing: Boolean) {
        ioExecutor.execute {
            dao.insert(
                ChatMessageEntity(
                    chatId = chatId,
                    content = content,
                    isOutgoing = isOutgoing
                )
            )
        }
    }

    fun loadMessages(chatId: String, onLoaded: (List<ChatMessageEntity>) -> Unit) {
        ioExecutor.execute {
            val messages = dao.getMessagesForChat(chatId)
            mainHandler.post { onLoaded(messages) }
        }
    }
}
