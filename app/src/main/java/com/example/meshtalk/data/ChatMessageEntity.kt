package com.example.meshtalk.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    indices = [Index(value = ["chatId"]), Index(value = ["timestamp"])]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val chatId: String,
    val content: String,
    val isOutgoing: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)
