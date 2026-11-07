package com.example.prisma

import android.content.DialogInterface
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
import com.example.prisma.data.db.generateMessageId
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ChatActivity : AppCompatActivity() {
    private val dao by lazy {
        PrismaDatabase.getInstance(this).getDao()
    }
    private var contact: ContactEntity? = null
    private var contactToolbarTitle = ""
    private lateinit var adapter: ChatAdapter
    private lateinit var toolbar: MaterialToolbar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_chat)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        toolbar = findViewById(R.id.mt_chatD_toolbar)
        val rv = findViewById<RecyclerView>(R.id.rv_chat_viewMessages)
        val etMessage = findViewById<EditText>(R.id.et_chat_enterMessage)
        val sendBtn = findViewById<ImageButton>(R.id.btn_chat_sendMessage)

        adapter = ChatAdapter(
            sendBtn = sendBtn,
            onSelectionChanged = { count ->
                updateSelectionToolbar(count)
            },
            onDeleteMessage = { message ->
                showDeleteDialog(listOf(message))
            }
        )
        rv.adapter = adapter

        toolbar.setNavigationOnClickListener {
            finish()
        }

        val contactId = intent.getLongExtra("contactId", 0)

        lifecycleScope.launch {
            contact = dao.getContactById(contactId)

            contactToolbarTitle = contact?.name
                ?.takeIf { it.isNotBlank() }
                ?: contact?.pubKey?.take(8)?.uppercase()
                        ?: ""

            updateSelectionToolbar(0)

            val client = (application as PrismaApp).client

            client.onGetContactOnline { isOnline ->
                toolbar.post {
                    animateToolbarSubtitle(
                        toolbar,
                        if (isOnline) "В сети" else "Не в сети"
                    )
                }
            }

            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (isActive) {
                        client.contactIsOnline(contact?.pubKey ?: "")
                        delay(5_000)
                    }
                }
            }

            dao.setMessageIsRead(contactId)
            toolbar.setOnClickListener {
                showNewChatsDialog(toolbar)
            }

            sendBtn.setOnClickListener {
                val clientMessageId = generateMessageId()
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
                    serverId = null,
                    messageClientId = clientMessageId
                )

                lifecycleScope.launch {
                    val id = dao.insertMessage(sendMsg)
                    etMessage.text.clear()
                    if (id == -1L) return@launch

                    val ok = (application as PrismaApp).client.send(
                        receiverKey = c.pubKey,
                        text = sendText,
                        clientMsgId = clientMessageId
                    )
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

    private fun updateSelectionToolbar(count: Int) {
        toolbar.menu.clear()

        if (count > 0) {
            toolbar.title = "Выбрано: $count"

            toolbar.menu.add("Удалить").apply {
                setIcon(R.drawable.ic_delete)
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)

                setOnMenuItemClickListener {
                    showDeleteDialog(adapter.getSelectedMessages())
                    true
                }
            }
        } else {
            toolbar.title = contactToolbarTitle
        }
    }

    private fun showDeleteDialog(messages: List<MessageEntity>) {
        val selected = messages.distinctBy { it.id }

        if (selected.isEmpty()) return

        // Удалять у всех можно только собственные
        // отправленные сообщения.
        val canDeleteForEveryone = selected.size <= 100 &&
                selected.all {
                    it.direction == MessageDirection.OUT &&
                            it.status == MessageStatus.SENT &&
                            it.messageClientId.isNotBlank()
                }

        val checkBox = MaterialCheckBox(this).apply {
            text = "Удалить у всех"
            isEnabled = canDeleteForEveryone
        }

        val density = resources.displayMetrics.density

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (24 * density).toInt(),
                (8 * density).toInt(),
                (24 * density).toInt(),
                (8 * density).toInt()
            )

            addView(checkBox)
        }

        if (!canDeleteForEveryone) {
            val hint = TextView(this).apply {
                text = if (selected.size > 100) {
                    "За один раз можно удалить у всех не более 100 сообщений."
                } else {
                    "Удаление у всех доступно только для собственных отправленных сообщений."
                }
                textSize = 12f
            }

            content.addView(hint)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(
                if (selected.size == 1) "Удалить сообщение?"
                else "Удалить сообщения?"
            )
            .setView(content)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener {

                    lifecycleScope.launch {

                        val deleteForEveryone = checkBox.isChecked

                        if (deleteForEveryone) {
                            val c = contact ?: return@launch

                            val client = (application as PrismaApp).client

                            val success = client.deleteMessagesForEveryone(
                                receiverKey = c.pubKey,
                                clientMessageIds = selected.map {
                                    it.messageClientId
                                }
                            )

                            if (!success) {
                                Toast.makeText(
                                    this@ChatActivity,
                                    "Не удалось отправить запрос удаления",
                                    Toast.LENGTH_SHORT
                                ).show()

                                return@launch
                            }
                        }

                        // В обоих режимах удаляем локальные записи.
                        dao.deleteMessagesByIds(
                            selected.map { it.id }
                        )

                        adapter.clearSelection()

                        dialog.dismiss()
                    }
                }
        }

        dialog.show()
    }

    private fun animateToolbarSubtitle(
        toolbar: MaterialToolbar,
        newText: String
    ) {
        if (toolbar.subtitle?.toString() == newText) return

        val subtitleView = toolbar.findSubtitleTextView()

        if (subtitleView == null) {
            toolbar.subtitle = newText
            return
        }

        val offset = 3f * toolbar.resources.displayMetrics.density

        subtitleView.animate().cancel()

        subtitleView.animate()
            .alpha(0f)
            .translationY(-offset)
            .setDuration(45)
            .withEndAction {

                toolbar.subtitle = newText

                subtitleView.alpha = 0f
                subtitleView.translationY = offset

                subtitleView.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(55)
                    .start()
            }
            .start()
    }

    private fun MaterialToolbar.findSubtitleTextView(): TextView? {
        val target = subtitle?.toString() ?: return null

        fun search(parent: ViewGroup): TextView? {
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)

                if (child is TextView &&
                    child.text.toString() == target &&
                    child.text.toString() != title?.toString()
                ) {
                    return child
                }

                if (child is ViewGroup) {
                    search(child)?.let { return it }
                }
            }

            return null
        }

        return search(this)
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
                    sheet.dismiss()

                    dao.updateNameToContact(contact!!.id, newName)

                    contactToolbarTitle = newName.ifBlank {
                        contact?.pubKey?.take(8)?.uppercase() ?: ""
                    }
                    toolbar.title = contactToolbarTitle
                }
            }
        }
        sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        sheet.show()
    }
}