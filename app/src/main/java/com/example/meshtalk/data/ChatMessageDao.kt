package com.example.meshtalk.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ChatMessageDao {
    @Insert
    fun insert(message: ChatMessageEntity): Long

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC, id ASC")
    fun getMessagesForChat(chatId: String): List<ChatMessageEntity>
}
