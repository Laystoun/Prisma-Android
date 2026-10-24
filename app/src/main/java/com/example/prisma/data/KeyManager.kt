package com.example.prisma.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Sign
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import androidx.core.content.edit

private const val PREF_PUBLIC = "public_key"
private const val PREF_PRIVATE_ENC = "private_key_enc"
private const val PREF_IV = "private_key_iv"

object KeyManager {
    const val AES_ALIAS = "prisma_warp_key"
    const val KEYS_FILENAME = "prisma_key"

    private val state = LazySodiumAndroid(SodiumAndroid())

    private fun getPrefs(context: Context): SharedPreferences
    {
        return context.getSharedPreferences(KEYS_FILENAME, Context.MODE_PRIVATE)
    }

    fun hasKeys(context: Context): Boolean {
        return getPrefs(context).getString(PREF_PUBLIC, null) != null
                && getPrefs(context).getString(PREF_PRIVATE_ENC, null) != null
                && getPrefs(context).getString(PREF_IV, null) != null
    }

    fun generateAndSave(context: Context) {
        if (hasKeys(context)) return

        val publicKey = ByteArray(Sign.PUBLICKEYBYTES)
        val secretKey = ByteArray(Sign.SECRETKEYBYTES)
        val ok = state.cryptoSignKeypair(publicKey, secretKey)
        if (!ok) throw IllegalStateException("Не удалось создать ключи")

        val (encrypted, iv) = encrypt(secretKey)
        secretKey.fill(0)

        getPrefs(context).edit {
            putString(PREF_PRIVATE_ENC, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(PREF_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .putString(PREF_PUBLIC, Base64.encodeToString(publicKey, Base64.NO_WRAP))
        }
    }

    fun getPublicKey(context: Context): ByteArray {
        val saved = getPrefs(context).getString(PREF_PUBLIC, null)
            ?: throw IllegalStateException("Ключи ещё не созданы")
        return Base64.decode(saved, Base64.NO_WRAP)
    }

    private fun getOrCreateKeyAes(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        if (!keyStore.containsAlias(AES_ALIAS)) {
            val generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore")
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
    /*Encryption module*/
    private fun encrypt(data: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKeyAes())
        val encrypted = cipher.doFinal(data)
        return Pair(encrypted, cipher.iv)
    }

    private fun decrypt(cipherText: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKeyAes(), GCMParameterSpec(128, iv))
        return cipher.doFinal(cipherText)
    }

    fun sign(context: Context, nonce: ByteArray): ByteArray {
        val prefs = getPrefs(context)
        val encB64 = prefs.getString(PREF_PRIVATE_ENC, null)
        val ivB64 = prefs.getString(PREF_IV, null)
        if (encB64 == null || ivB64 == null) throw IllegalStateException("Ключи ещё не созданы")

        val secretKey = decrypt(
            Base64.decode(encB64, Base64.NO_WRAP),
            Base64.decode(ivB64, Base64.NO_WRAP)
        )
        try {
            return ed25519Sign(nonce, secretKey)
        } finally {
            secretKey.fill(0)
        }
    }

    private fun ed25519Sign(nonce: ByteArray, secretKey: ByteArray): ByteArray {
        val sig = ByteArray(Sign.BYTES)
        val ok = state.cryptoSignDetached(sig, nonce, nonce.size.toLong(), secretKey)
        if (!ok) throw IllegalStateException("Не удалось подписать")
        return sig
    }
}