package com.example.prisma.data

import android.content.Context
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONException
import org.json.JSONObject

object PRISMA_CONST {
    /*CLIENT*/
    const val CLIENT_HELLO = "hello"
    const val CLIENT_AUTH = "auth"

    /*SERVER*/
    const val SERVER_CHALLENGE = "challenge"
    const val SERVER_OK = "ok"
}

class PrismaClient(
    private val context: Context,
    private val publicKey: ByteArray
) {
    private val URL = "wss://tel-c3e.plurio.biz/ws"
    private val http = OkHttpClient()
    private var socket: WebSocket? = null

    @Volatile var authorized = false

    fun start() {
        val request = Request.Builder().url(URL).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                super.onOpen(webSocket, response)
                authorized = false
                val jsonObj = JSONObject()
                jsonObj.put("type", PRISMA_CONST.CLIENT_HELLO)
                jsonObj.put("pub_key", Base64.encodeToString(publicKey, Base64.NO_WRAP))
                webSocket.send(jsonObj.toString())
                Log.d("WS", "Соединение WS Установлено. HEADER: ${response.headers} ")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                super.onFailure(webSocket, t, response)
                authorized = false
                Log.d("WS", "Ошибка соединения: ${t.message}")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                super.onMessage(webSocket, text)
                Log.d("WS", "Ответ сервера: $text")

                try {
                    val jsonObj = JSONObject(text)
                    val type = jsonObj.getString("type")

                    when (type) {
                        PRISMA_CONST.SERVER_CHALLENGE -> {
                            val n = jsonObj.getString("nonce")
                            val nonce = Base64.decode(n, Base64.NO_WRAP)
                            val sign = KeyManager.sign(context, nonce)

                            val authChallenge = JSONObject()
                            authChallenge.put("type", PRISMA_CONST.CLIENT_AUTH)
                            authChallenge.put("sign", Base64.encodeToString(sign, Base64.NO_WRAP))

                            webSocket.send(authChallenge.toString())
                        }

                        PRISMA_CONST.SERVER_OK -> {
                            authorized = true
                        }
                    }
                } catch (e: JSONException) {
                    Log.e("WS", "Ошибка парсинга JSON: $e")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                super.onClosed(webSocket, code, reason)
                authorized = false
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                super.onClosing(webSocket, code, reason)
                authorized = false
                webSocket.close(1000, null)
            }
        })
    }
}