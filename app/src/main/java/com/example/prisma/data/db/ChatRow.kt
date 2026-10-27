package com.example.prisma.data.db

data class ChatRow(
    val contactId: Long,
    val pubKey: String,
    val name: String?,
    val lastBody: String?,
    val lastTime: Long?,
    val unread: Int
)
