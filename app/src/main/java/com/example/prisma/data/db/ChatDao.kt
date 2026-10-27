package com.example.prisma.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao interface ChatDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertContact(contact: ContactEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("SELECT * FROM ContactEntity WHERE pubKey = :pubKey")
    suspend fun getContactByKey(pubKey: String): ContactEntity?

    @Query("SELECT * FROM MessageEntity WHERE contactId = :contactId ORDER BY createdAt ASC")
    fun getMessages(contactId: Long): Flow<List<MessageEntity>>

    @Query("""
        SELECT c.id AS contactId,
               c.pubKey AS pubKey,
               c.name AS name,
               (SELECT body FROM MessageEntity
                 WHERE contactId = c.id
                 ORDER BY createdAt DESC LIMIT 1) AS lastBody,
               (SELECT createdAt FROM MessageEntity
                 WHERE contactId = c.id
                 ORDER BY createdAt DESC LIMIT 1) AS lastTime,
               (SELECT COUNT(*) FROM MessageEntity
                 WHERE contactId = c.id AND direction = 0 AND isRead = 0) AS unread
        FROM ContactEntity c
        ORDER BY lastTime DESC
    """)
    fun getChats(): Flow<List<ChatRow>>

    @Query("SELECT * FROM ContactEntity WHERE id = :id")
    suspend fun getContactById(id: Long): ContactEntity?

    @Query("UPDATE MessageEntity SET isRead = 1 WHERE contactId = :contactId AND direction = 0")
    suspend fun setMessageIsRead(contactId: Long)
}