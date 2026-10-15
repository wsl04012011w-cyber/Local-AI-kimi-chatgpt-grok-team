package dev.localai.chat.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages ORDER BY createdAt ASC, id ASC")
    fun observeMessages(): Flow<List<MessageEntity>>

    @Insert
    suspend fun insert(entity: MessageEntity): Long

    @Query("UPDATE messages SET content = :content, thinking = :thinking WHERE id = :id")
    suspend fun updateContent(id: Long, content: String, thinking: String)

    @Query("DELETE FROM messages")
    suspend fun clear()
}
