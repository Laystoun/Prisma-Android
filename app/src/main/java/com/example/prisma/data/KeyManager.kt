package com.example.prisma.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Sign
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

object KeyManager {
    const val AES_ALIAS = "prisma_warp_key"
    const val KEYS_FILENAME = "prisma_key"

    val state = LazySodiumAndroid(SodiumAndroid())
    val pb_key = ByteArray(Sign.PUBLICKEYBYTES)
    val prv_key = ByteArray(Sign.SECRETKEYBYTES)

    init {

    }

    fun generateKeys() {
        state.cryptoSignKeypair(pb_key, prv_key)
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(KEYS_FILENAME, Context.MODE_PRIVATE)
    }

    private fun getOrCreateKeyAes(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        if (!keyStore.containsAlias(AES_ALIAS)) {
            val generator = KeyGenerator.getInstance("AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(
                AES_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )   .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()

            generator.init(spec)
            generator.generateKey()
        }

        return keyStore.getKey(AES_ALIAS, null) as SecretKey
    }

    
}