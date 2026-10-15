package dev.localai.chat.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.localai.chat.data.db.ChatDatabase
import dev.localai.chat.data.db.MessageDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideChatDatabase(@ApplicationContext context: Context): ChatDatabase =
        Room.databaseBuilder(context, ChatDatabase::class.java, "local-ai.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideMessageDao(database: ChatDatabase): MessageDao = database.messageDao()
}
