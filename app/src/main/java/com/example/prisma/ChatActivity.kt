package com.example.prisma

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.example.prisma.data.ChatAdapter
import com.example.prisma.data.db.ContactEntity
import com.example.prisma.data.db.MessageDirection
import com.example.prisma.data.db.MessageEntity
import com.example.prisma.data.db.MessageStatus
import com.example.prisma.data.db.PrismaDatabase
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ChatActivity : AppCompatActivity() {
    private val dao by lazy {
        PrismaDatabase.getInstance(this).getDao()
    }
    private var contact: ContactEntity? = null
    private lateinit var adapter: ChatAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_chat)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val toolbar = findViewById<MaterialToolbar>(R.id.mt_chatD_toolbar)
        val rv = findViewById<RecyclerView>(R.id.rv_chat_viewMessages)
        val etMessage = findViewById<EditText>(R.id.et_chat_enterMessage)
        val sendBtn = findViewById<ImageButton>(R.id.btn_chat_sendMessage)

        adapter = ChatAdapter(sendBtn)
        rv.adapter = adapter

        toolbar.setNavigationOnClickListener {
            finish()
        }

        val contactId = intent.getLongExtra("contactId", 0)

        lifecycleScope.launch {
            contact = dao.getContactById(contactId)
            toolbar.title = contact?.name ?: contact?.pubKey?.take(8)?.uppercase()

            val client = (application as PrismaApp).client

            client.onGetContactOnline { isOnline ->
                toolbar.post {
                    toolbar.subtitle = if (isOnline) "В сети" else "Не в сети"
                }
            }

            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (isActive) {
                        client.contactIsOnline(contact?.pubKey ?: "")
                        delay(7_000)
                    }
                }
            }

            dao.setMessageIsRead(contactId)
            toolbar.setOnClickListener {
                showNewChatsDialog(toolbar)
            }

            sendBtn.setOnClickListener {
                val c = contact ?: return@setOnClickListener
                val sendText = etMessage.text.toString().trim()
                if (sendText.isEmpty()) return@setOnClickListener

                val sendMsg = MessageEntity(
                    contactId = contactId,
                    direction = MessageDirection.OUT,
                    body = sendText,
                    createdAt = System.currentTimeMillis(),
                    status = MessageStatus.SENDING,
                    isRead = true,
                    serverId = null
                )

                lifecycleScope.launch {
                    val id = dao.insertMessage(sendMsg)
                    etMessage.text.clear()
                    if (id == -1L) return@launch

                    val ok = (application as PrismaApp).client.send(c.pubKey, sendText)
                    dao.updateStatus(id, if (ok) MessageStatus.SENT else MessageStatus.ERROR)
                }
            }

            repeatOnLifecycle(Lifecycle.State.STARTED) {
                dao.getMessages(contactId).collect { list ->
                    adapter.submit(list)
                    if (list.isNotEmpty()) rv.scrollToPosition(list.size - 1)
                }
            }
        }
    }

    fun showNewChatsDialog(
        toolbar: MaterialToolbar
    ) {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_edit_name_contact, null)
        sheet.setContentView(view)

        val sheetView = sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)

        view.findViewById<Button>(R.id.btn_sheetEditNameReady).setOnClickListener {
            val newName = view.findViewById<EditText>(R.id.et_sheetEditName).text.toString()
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    dao.updateNameToContact(contact!!.id, newName)
                    toolbar.title = newName
                    sheet.dismiss()
                }
            }
        }
        sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        sheet.show()
    }
}