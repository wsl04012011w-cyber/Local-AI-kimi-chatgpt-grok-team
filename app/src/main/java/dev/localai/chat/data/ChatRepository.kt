package dev.localai.chat.data

import dev.localai.chat.data.db.MessageDao
import dev.localai.chat.data.db.MessageEntity
import dev.localai.chat.ui.chat.Role
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(
    private val messageDao: MessageDao,
) {
    fun observeMessages() = messageDao.observeMessages()

    suspend fun addMessage(role: Role, content: String): Pair<Long, MessageEntity> {
        val entity = MessageEntity(role = role.name.lowercase(), content = content)
        return messageDao.insert(entity) to entity
    }

    suspend fun updateContent(id: Long, content: String, thinking: String = "") =
        messageDao.updateContent(id, content, thinking)

    suspend fun clear() = messageDao.clear()
}
