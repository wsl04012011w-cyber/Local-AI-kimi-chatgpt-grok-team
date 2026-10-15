package dev.localai.chat.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String, // "user" | "assistant"
    val content: String,
    val thinking: String = "", // raciocinio do modelo (<think>), persistido para a caixa nao sumir
    val createdAt: Long = System.currentTimeMillis(),
)
