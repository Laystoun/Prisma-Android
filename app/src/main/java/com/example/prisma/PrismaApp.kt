package com.example.prisma

import android.app.Application
import com.example.prisma.data.KeyManager
import com.example.prisma.data.PrismaClient

class PrismaApp : Application() {
    lateinit var client: PrismaClient

    override fun onCreate() {
        super.onCreate()

        initClient()
    }

    fun initClient() {
        if (!::client.isInitialized && KeyManager.hasKeys(this)) {
            val pubKey = KeyManager.getPublicKey(this)
            client = PrismaClient(
                this,
                pubKey
            )
            client.start()

        }
    }
}