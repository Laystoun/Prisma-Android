package com.example.prisma

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.example.prisma.data.KeyManager
import com.example.prisma.data.db.ContactEntity
import com.example.prisma.data.db.PrismaDatabase
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.jar.Manifest

class ChatsActivity : AppCompatActivity() {
    private val dao by lazy {
        PrismaDatabase.getInstance(this).getDao()
    }
    private var selectedCount = 0
    private var isConnected = false
    lateinit var chatsAdapter: ChatsAdapter
    lateinit var toolbar: MaterialToolbar

    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->

            if (!isGranted)
            {
                showWindowAccessNotify()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_chats)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        val client = (application as PrismaApp).client

        getPermissionOnNotify()

        toolbar = findViewById<MaterialToolbar>(R.id.mt_chat_toolbar)
        val myKeyBtn = findViewById<MaterialCardView>(R.id.cv_myKey)
        val newChatBtn = findViewById<Button>(R.id.btn_chats_newChat)
        newChatBtn.setOnClickListener {
            showNewChatsDialog()
        }

        myKeyBtn.setOnClickListener {
            val clipboard = this.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val pubKeyBase64 = Base64.encodeToString(KeyManager.getPublicKey(this), Base64.NO_WRAP)
            val clip = ClipData.newPlainText("Мой ключ", pubKeyBase64)
            clipboard.setPrimaryClip(clip)
        }

        fun updateTitle() {
            toolbar.title = when {
                selectedCount > 0 -> "Выбрано: $selectedCount"
                isConnected -> "Prisma"
                else -> "Подключение..."
            }
        }

        chatsAdapter = ChatsAdapter(
            onClick = { chatRow ->
                val intent = Intent(this, ChatActivity::class.java)
                intent.putExtra("contactId", chatRow.contactId)
                startActivity(intent)
            },
            onSelectionChanged = { count ->
                selectedCount = count
                updateTitle()
                updateMenu()
            }
        )

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_delete -> {
                    confirmDelete()
                    true
                }
                else -> false
            }
        }

        val rv_chats = findViewById<RecyclerView>(R.id.rv_chats)
        rv_chats.adapter=chatsAdapter
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                client.connected.collect {isConn ->
                    isConnected = isConn
                    updateTitle()
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                dao.getChats().collect { list ->
                    chatsAdapter.submit(list)
                }
            }
        }
    }

    private fun updateMenu() {
        val selecting = selectedCount > 0
        toolbar.menu.findItem(R.id.action_search)?.isVisible = !selecting
        toolbar.menu.findItem(R.id.action_delete)?.isVisible = selecting
    }

    private fun confirmDelete() {
        val toDelete = chatsAdapter.getSelectedIds()
        if (toDelete.isEmpty()) return

        val btmSheet = BottomSheetDialog(this)
        val sheetInflate = layoutInflater.inflate(R.layout.bottom_sheet_delete_contacts, null)
        btmSheet.setContentView(sheetInflate)

        sheetInflate.findViewById<Button>(R.id.btn_sheetDeleteChats).setOnClickListener {
            lifecycleScope.launch {
                toDelete.map { id -> dao.deleteContact(id) }
                chatsAdapter.clearSelection()
                btmSheet.dismiss()
            }
        }

        btmSheet.show()
    }

    private fun showNewChatsDialog() {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_new_chat, null)
        sheet.setContentView(view)

        view.findViewById<View>(R.id.btn_sheetNewChat_insertKey).setOnClickListener {
            showPasteKeySheet(sheet)
        }
        sheet.show()
        sheet.setupHalfScreen()
    }
    private fun showPasteKeySheet(parent: BottomSheetDialog) {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_insert_key, null)
        sheet.setContentView(view)
        sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val etKey = view.findViewById<EditText>(R.id.et_sheetInsertKey_pasteKey)
        val tvError = view.findViewById<TextView>(R.id.tv_pasteKey_error)
        val btnContinue = view.findViewById<View>(R.id.btn_sheetInsertKey_pasteKeyContinue)

        btnContinue.setOnClickListener {
            val raw = etKey.text.toString().trim()
            val bytes = try {
                Base64.decode(raw, Base64.NO_WRAP)
            } catch (e: IllegalArgumentException) {
                null
            }

            if (bytes == null || bytes.size != 32) {
                tvError.text = "Ключ должен быть в base64 и ровно 32 байта"
                tvError.visibility = View.VISIBLE
                return@setOnClickListener
            }

            val key = Base64.encodeToString(bytes, Base64.NO_WRAP)
            lifecycleScope.launch {
                if (dao.getContactByKey(key) == null) {
                    dao.insertContact(ContactEntity(pubKey = key, name = null, created = System.currentTimeMillis()))
                }
                sheet.dismiss()
                parent.dismiss()
            }
        }
        sheet.show()
    }

    private fun getPermissionOnNotify() {
        /*on Android 12 and below there is no separate permission*/
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }

        val permission = android.Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED)
        {
            return
        }

        notificationPermissionLauncher.launch(permission)
    }

    private fun showWindowAccessNotify() {
        val bottomSheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(
            R.layout.bottom_sheet_denied_notif_perm,null
        )

        bottomSheet.setContentView(view)

        view.findViewById<Button>(R.id.btn_sheetDeniedNotifPerm_getPerm).setOnClickListener {
            bottomSheet.dismiss()
            if (shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)) {
                getPermissionOnNotify()
            } else {
                openNotificationSettings()
            }
        }

        bottomSheet.show()
    }

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        startActivity(intent)
    }
}

fun BottomSheetDialog.setupHalfScreen() {
    val bottomSheet = findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
    bottomSheet?.let { sheetView ->
        val behavior = BottomSheetBehavior.from(sheetView)
        val screenHeight = context.resources.displayMetrics.heightPixels
        val halfHeight = screenHeight / 2

        sheetView.layoutParams.height = halfHeight

        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.peekHeight = halfHeight
    }
}