package com.example.prisma

import android.os.Bundle
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.prisma.data.KeyManager
import com.example.prisma.data.PrismaClient
import com.example.prisma.data.db.ContactEntity
import com.example.prisma.data.db.MessageEntity
import com.example.prisma.data.db.MessageStatus
import com.example.prisma.data.db.PrismaDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ChatsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_chats)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        val pubKey = KeyManager.getPublicKey(this)
        val client = PrismaClient(this, pubKey)
        client.start()
    }
}