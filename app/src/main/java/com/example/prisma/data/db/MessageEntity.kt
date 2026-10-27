package com.example.prisma.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
* contactId: Long (чьё это сообщение, ссылка на ContactEntity.id)
direction: Int (0 входящее, 1 исходящее)
body: String (текст)
createdAt: Long (миллисекунды)
status: Int (отправляется, отправлено, ошибка)
isRead: Boolean
serverId: Long? (message_id от сервера, у исходящих null)
* */

@Entity(
    foreignKeys = [ForeignKey(
        entity = ContactEntity::class,
        parentColumns = ["id"],
        childColumns = ["contactId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index("contactId"),
        Index(value = ["serverId"], unique = true)
    ]
)

data class MessageEntity(
    @PrimaryKey (autoGenerate = true)
    val id: Long = 0,
    val contactId: Long,
    val direction: Int,
    val body: String,
    val createdAt: Long,
    val status: Int,
    val isRead: Boolean,
    val serverId: Long?
)