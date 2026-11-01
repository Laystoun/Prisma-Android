package com.example.prisma

import android.util.Base64

fun parsePublicKey(raw: String): String? {
    val bytes = try {
        Base64.decode(raw.trim(), Base64.NO_WRAP)
    } catch (e: IllegalArgumentException) {
        null
    }

    if (bytes == null || bytes.size != 32) return null

    return Base64.encodeToString(bytes, Base64.NO_WRAP)
}