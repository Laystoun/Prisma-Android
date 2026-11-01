package com.example.prisma.data

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.prisma.data.db.ContactEntity
import com.example.prisma.data.db.MessageDirection
import com.example.prisma.data.db.MessageEntity
import com.example.prisma.data.db.MessageStatus
import com.example.prisma.data.db.PrismaDatabase
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONException
import org.json.JSONObject

object PRISMA_CONST {
    /*CLIENT*/
    const val CLIENT_HELLO = "hello"
    const val CLIENT_AUTH = "auth"
    const val CLIENT_SEND = "send"
    const val CLIENT_ACK = "ack"
    const val CLIENT_PUSH_TOKEN = "push_token"

    /*SERVER*/
    const val SERVER_CHALLENGE = "challenge"
    const val SERVER_OK = "ok"
    const val SERVER_DELIVER = "deliver"
}

class PrismaClient(
    private val context: Context,
    private val publicKey: ByteArray
) {
    private val URL = "wss://prisma.plurio.biz/ws"
    private val http = OkHttpClient()
    private var socket: WebSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao by lazy { PrismaDatabase.getInstance(context).getDao() }
    private val conn = MutableStateFlow(false)
    val connected = conn.asStateFlow()

    val reconnecting = AtomicBoolean(false)
    val retryDelay: Long = 1000

    @Volatile private var attempts = 0
    @Volatile var authorized = false
    @Volatile private var generation = 0

    fun start() {
        val request = Request.Builder().url(URL).build()
        val gen = ++generation
        socket?.cancel()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                super.onOpen(webSocket, response)
                conn.value = false
                authorized = false

                val jsonObj = JSONObject()
                jsonObj.put("type", PRISMA_CONST.CLIENT_HELLO)
                jsonObj.put("pub_key", Base64.encodeToString(publicKey, Base64.NO_WRAP))
                webSocket.send(jsonObj.toString())
                Log.d("WS", "Соединение WS Установлено. HEADER: ${response.headers} ")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen != generation) { return }
                super.onFailure(webSocket, t, response)
                authorized = false
                conn.value = false
                scheduleReconnect()
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
                            conn.value = true
                            attempts = 0

                            pushToken(webSocket)
                        }

                        PRISMA_CONST.SERVER_DELIVER -> {
                            val messageId = jsonObj.getLong("message_id")
                            val from = jsonObj.getString("from")
                            val blob = jsonObj.getString("blob")
                            scope.launch { handleDeliver(webSocket, messageId, from, blob) }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("WS", "Ошибка парсинга JSON: $e")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (gen != generation) { return }
                super.onClosed(webSocket, code, reason)
                authorized = false
                conn.value = false

                scheduleReconnect()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (gen != generation) { return }
                super.onClosing(webSocket, code, reason)
                authorized = false
                conn.value = false
                webSocket.close(1000, null)
            }
        })
    }

    fun send(receiverKey: String, text: String): Boolean {
        if (!authorized) return false

        val blob: String

        try {
            val decodePubKey = Base64.decode(receiverKey, Base64.NO_WRAP)
            val encrypt = KeyManager.encryptFor(context, decodePubKey, text)
            blob = Base64.encodeToString(encrypt, Base64.NO_WRAP)
        } catch (e: Exception) {
            return false
        }

        val json = JSONObject()
        json.put("type", PRISMA_CONST.CLIENT_SEND)
        json.put("to", receiverKey)
        json.put("blob", blob)

        return socket?.send(json.toString()) ?: false
    }

    private suspend fun handleDeliver(
        webSocket: WebSocket,
        messageId: Long,
        from: String,
        blob: String
    ) {
        try {
            val fromBytes = Base64.decode(from, Base64.NO_WRAP)
            val blobBytes = Base64.decode(blob, Base64.NO_WRAP)

            val body = KeyManager.decryptFor(context, fromBytes, blobBytes)

            if (body == null) {
                Log.w("WS", "[PrismaClient.kt] .decryptFor вернула null")
                val ack = JSONObject()
                ack.put("type", PRISMA_CONST.CLIENT_ACK)
                ack.put("message_id", messageId)
                webSocket.send(ack.toString())

                return
            }

            var contact = dao.getContactByKey(from)
            if (contact == null) {
                dao.insertContact(
                    ContactEntity(pubKey = from, name = null, created = System.currentTimeMillis())
                )
                contact = dao.getContactByKey(from)
            }
            if (contact == null) return

            dao.insertMessage(
                MessageEntity(
                    contactId = contact.id,
                    direction = MessageDirection.IN,
                    body = body,
                    createdAt = System.currentTimeMillis(),
                    status = MessageStatus.SENT,
                    isRead = false,
                    serverId = messageId
                )
            )

            val ack = JSONObject()
            ack.put("type", PRISMA_CONST.CLIENT_ACK)
            ack.put("message_id", messageId)
            webSocket.send(ack.toString())
        } catch (e: Exception) {
            Log.e("WS", "Ошибка обработки deliver: $e")
        }
    }

    fun scheduleReconnect() {
        if (!reconnecting.compareAndSet(false, true)) return

        val delayMs = if (attempts < 5) 1000L
                      else minOf(1000L + (attempts - 4) * 500L, 15000L)
        attempts++

        scope.launch {
            delay(delayMs)
            reconnecting.set(false)
            start()
        }
    }

    fun pushToken(websocket: WebSocket) {
        scope.launch {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                val tokenJson = JSONObject()
                tokenJson.put("type", PRISMA_CONST.CLIENT_PUSH_TOKEN)
                tokenJson.put("token", token)
                websocket.send(tokenJson.toString())

                Log.d("WS", "Токен успешно отправлен")
            }
        }
    }

    fun sendNewToken(token: String) {
        if (!authorized) return

        val tokenJson = JSONObject()
        tokenJson.put("type", PRISMA_CONST.CLIENT_PUSH_TOKEN)
        tokenJson.put("token", token)
        socket?.send(tokenJson.toString())
    }
}