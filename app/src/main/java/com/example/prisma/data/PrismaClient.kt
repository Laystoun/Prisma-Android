package com.example.prisma.data

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.prisma.data.db.ContactEntity
import com.example.prisma.data.db.MessageDirection
import com.example.prisma.data.db.MessageEntity
import com.example.prisma.data.db.MessageStatus
import com.example.prisma.data.db.PrismaDatabase
import com.example.prisma.data.db.generateMessageId
import com.google.android.material.appbar.MaterialToolbar
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
import org.json.JSONArray
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
    const val CLIENT_STATUS_REQUEST = "client_status_request"
    const val CLIENT_DELETE_MESSAGE = "client_deleted_message"
    const val CLIENT_EVENT_ACK = "client_event_ack"

    /*SERVER*/
    const val SERVER_CHALLENGE = "challenge"
    const val SERVER_OK = "ok"
    const val SERVER_DELIVER = "deliver"
    const val SERVER_STATUS_RESPONSE = "server_status_response"
    const val SERVER_EVENT = "event"


    const val EVENT_DELETE_MESSAGE = "delete_message"
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

    private var onContactOnlineCallback: ((Boolean) -> Unit)? = null

    fun onGetContactOnline(callback: (Boolean) -> Unit) {
        onContactOnlineCallback = callback
    }

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
                            Log.d("WS", "Message:\n${jsonObj}")
                            val messageId = jsonObj.getLong("message_id")
                            val from = jsonObj.getString("from")
                            val blob = jsonObj.getString("blob")
                            val clientMessageId = jsonObj.getString("client_message_id")
                            val sendAt = jsonObj.optLong("send_at", 0L)

                            scope.launch {
                                handleDeliver(
                                    webSocket,
                                    messageId,
                                    from,
                                    blob,
                                    clientMessageId,
                                    sendAt
                                )
                            }
                        }

                        PRISMA_CONST.SERVER_STATUS_RESPONSE -> {
                            val pubKey = jsonObj.getString("pub_key")
                            val online = jsonObj.getBoolean("online")

                            onContactOnlineCallback?.invoke(online)

                            Log.d("WS", "Контакт $pubKey online: $online")
                        }

                        PRISMA_CONST.SERVER_EVENT -> {
                            val requestId = jsonObj.getString("request_id")
                            val from = jsonObj.getString("from")
                            val eventType = jsonObj.getString("event_type")
                            val payload = jsonObj.getJSONObject("payload")

                            when (eventType) {
                                PRISMA_CONST.EVENT_DELETE_MESSAGE -> {
                                    scope.launch {
                                        handleDeleteMessageEvent(
                                            webSocket,
                                            requestId,
                                            from,
                                            payload
                                        )
                                    }
                                }

                                else -> {
                                    Log.w("WS", "Unknown event: $eventType")
                                }
                            }
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

    private suspend fun handleDeleteMessageEvent(
        webSocket: WebSocket,
        requestId: String,
        from: String,
        payload: JSONObject
    ) {
        try {
            val ids = payload.getJSONArray("client_message_ids")

            val contact = dao.getContactByKey(from)

            if (contact != null) {
                for (i in 0 until ids.length()) {

                    val clientMessageId = ids.getString(i)

                    val message = dao.getMessageByClientId(
                        contactId = contact.id,
                        direction = MessageDirection.IN,
                        clientMessageId = clientMessageId
                    )

                    if (message == null) continue

                    dao.deleteMessageById(message.id)
                }
            }

            val ack = JSONObject().apply {
                put("type", PRISMA_CONST.CLIENT_EVENT_ACK)
                put("request_id", requestId)
            }

            webSocket.send(ack.toString())

            Log.d("WS", "Delete event processed: $requestId")

        } catch (e: Exception) {
            Log.e("WS", "Delete event error: $e")
        }
    }

    fun send(receiverKey: String, text: String, clientMsgId: String, sendAt: Long = System.currentTimeMillis()): Boolean {
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
        json.put("send_at", sendAt)
        json.put("client_message_id", clientMsgId)

        return socket?.send(json.toString()) ?: false
    }

    private suspend fun handleDeliver(
        webSocket: WebSocket,
        messageId: Long,
        from: String,
        blob: String,
        clientMessageId: String,
        sendAt: Long
    ) {
        try {
            val fromBytes = Base64.decode(from, Base64.NO_WRAP)
            val blobBytes = Base64.decode(blob, Base64.NO_WRAP)

            val body = KeyManager.decryptFor(context, fromBytes, blobBytes)

            if (body == null) {
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
                    createdAt = if (sendAt > 0) sendAt else System.currentTimeMillis(),
                    status = MessageStatus.SENT,
                    isRead = false,
                    serverId = messageId,
                    messageClientId = clientMessageId
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

    fun contactIsOnline(pubKey: String) {
        if (!authorized) return

        val request = JSONObject()
        request.put("type", PRISMA_CONST.CLIENT_STATUS_REQUEST)
        request.put("pub_key", pubKey)

        val sent = socket?.send(request.toString()) ?: false

        if (!sent) {
            Log.e("WS", "Не удалось отправить запрос статуса")
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

    fun deleteMessagesForEveryone(
        receiverKey: String,
        clientMessageIds: List<String>
    ): Boolean {

        if (!authorized) return false
        if (clientMessageIds.isEmpty() || clientMessageIds.size > 100) return false

        val validIds = clientMessageIds.all { id ->
            id.length == 32 && id.all { char ->
                char in '0'..'9' || char.lowercaseChar() in 'a'..'f'
            }
        }

        if (!validIds) return false

        val ids = JSONArray()

        clientMessageIds.forEach { id ->
            ids.put(id)
        }

        val payload = JSONObject().apply {
            put("client_message_ids", ids)
        }

        val request = JSONObject().apply {
            put("type", PRISMA_CONST.CLIENT_DELETE_MESSAGE)
            put("to", receiverKey)
            put("payload", payload)
        }

        return socket?.send(request.toString()) ?: false
    }

    fun sendNewToken(token: String) {
        if (!authorized) return

        val tokenJson = JSONObject()
        tokenJson.put("type", PRISMA_CONST.CLIENT_PUSH_TOKEN)
        tokenJson.put("token", token)
        socket?.send(tokenJson.toString())
    }
}