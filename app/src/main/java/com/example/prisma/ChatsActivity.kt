package com.example.prisma

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.example.prisma.data.KeyManager
import com.example.prisma.data.db.ContactEntity
import com.example.prisma.data.db.PrismaDatabase
import com.example.prisma.data.update.UpdateManager
import com.example.prisma.data.update.UpdateStatus
import com.github.alexzhirkevich.customqrgenerator.QrData
import com.github.alexzhirkevich.customqrgenerator.vector.QrCodeDrawable
import com.github.alexzhirkevich.customqrgenerator.vector.createQrVectorOptions
import com.github.alexzhirkevich.customqrgenerator.vector.style.QrVectorBallShape
import com.github.alexzhirkevich.customqrgenerator.vector.style.QrVectorColor
import com.github.alexzhirkevich.customqrgenerator.vector.style.QrVectorFrameShape
import com.github.alexzhirkevich.customqrgenerator.vector.style.QrVectorLogoPadding
import com.github.alexzhirkevich.customqrgenerator.vector.style.QrVectorLogoShape
import com.github.alexzhirkevich.customqrgenerator.vector.style.QrVectorPixelShape
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class ChatsActivity : AppCompatActivity() {
    private val dao by lazy {
        PrismaDatabase.getInstance(this).getDao()
    }
    private var selectedCount = 0
    private var isConnected = false
    lateinit var chatsAdapter: ChatsAdapter
    lateinit var toolbar: MaterialToolbar


    private lateinit var updateCard: MaterialCardView
    private lateinit var updateProgress: LinearProgressIndicator
    private lateinit var updatePercent: TextView
    private lateinit var updateSize: TextView

    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->

            if (!isGranted)
            {
                showWindowAccessNotify()
            }
        }

    private val cameraPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                openQrScanner()
            } else {
                showCameraDeniedSheet()
            }
        }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            tryInstallUpdate()
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
        updateCard = findViewById(R.id.card_update_progress)
        updateProgress = findViewById(R.id.progress_update)
        updatePercent = findViewById(R.id.tv_update_percent)
        updateSize = findViewById(R.id.tv_update_size)

        val client = (application as PrismaApp).client

        getPermissionOnNotify()

        supportFragmentManager.setFragmentResultListener(
            QrScannerSheet.REQUEST_KEY,
            this
        ) { _, bundle ->
            bundle.getString(QrScannerSheet.RESULT_KEY)?.let { key ->
                lifecycleScope.launch { addContactIfNew(key) }
            }
        }

        toolbar = findViewById<MaterialToolbar>(R.id.mt_chat_toolbar)
        val fabNewChat = findViewById<ExtendedFloatingActionButton>(R.id.fab_chats_newChat)
        fabNewChat.setOnClickListener {
            showChatsFabSheet()
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

    private fun showUpdateProgress(
        progress: Int,
        downloaded: String,
        total: String
    ) {
        updateCard.visibility = View.VISIBLE

        updateProgress.progress = progress
        updatePercent.text = "$progress%"
        updateSize.text = "$downloaded из $total"
    }

    private fun hideUpdateProgress() {
        updateCard.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this,
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
        tryInstallUpdate()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(downloadReceiver)
    }

    private fun tryInstallUpdate() {
        UpdateManager.installPendingIfReady(this)?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
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
                toDelete.forEach { id -> dao.deleteContact(id) }
                chatsAdapter.clearSelection()
                btmSheet.dismiss()
            }
        }

        btmSheet.show()
    }

    private fun showChatsFabSheet() {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_chats_fab, null)
        sheet.setContentView(view)

        view.findViewById<View>(R.id.cv_fab_scanQr).setOnClickListener {
            sheet.dismiss()
            onScanQrClicked()
        }

        view.findViewById<View>(R.id.cv_fab_importKey).setOnClickListener {
            showPasteKeySheet(sheet)
        }

        view.findViewById<View>(R.id.cv_fab_myKey).setOnClickListener {
            sheet.dismiss()
            openCopyKeySheet()
        }

        sheet.show()
    }

    private fun onScanQrClicked() {
        val permission = android.Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            openQrScanner()
        } else {
            cameraPermissionLauncher.launch(permission)
        }
    }

    private fun openQrScanner() {
        if (supportFragmentManager.findFragmentByTag(QrScannerSheet.TAG) != null) return
        QrScannerSheet().show(supportFragmentManager, QrScannerSheet.TAG)
    }

    private fun showCameraDeniedSheet() {
        val bottomSheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_denied_camera_perm, null)
        bottomSheet.setContentView(view)

        view.findViewById<Button>(R.id.btn_sheetDeniedCameraPerm_getPerm).setOnClickListener {
            bottomSheet.dismiss()
            if (shouldShowRequestPermissionRationale(android.Manifest.permission.CAMERA)) {
                cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
            } else {
                openAppSettings()
            }
        }

        bottomSheet.show()
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
        startActivity(intent)
    }

    private suspend fun addContactIfNew(key: String) {
        if (dao.getContactByKey(key) == null) {
            dao.insertContact(ContactEntity(pubKey = key, name = null, created = System.currentTimeMillis()))
        }
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
            val key = parsePublicKey(etKey.text.toString())

            if (key == null) {
                tvError.text = "Ключ должен быть в base64 и ровно 32 байта"
                tvError.visibility = View.VISIBLE
                return@setOnClickListener
            }

            lifecycleScope.launch {
                addContactIfNew(key)
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

    private fun formatSize(bytes: Long): String {
        val mb = bytes / 1024.0 / 1024.0
        return String.format(Locale.getDefault(), "%.1f МБ", mb)
    }

    fun observeUpdateDownload(downloadId: Long) {
        lifecycleScope.launch {
            updateCard.visibility = View.VISIBLE

            while (isActive) {
                val state = withContext(Dispatchers.IO) {
                    UpdateManager.getProgress(this@ChatsActivity, downloadId)
                }

                updateProgress.progress = state.progress
                updatePercent.text = "${state.progress}%"

                updateSize.text =
                    "${formatSize(state.downloaded)} из ${formatSize(state.total)}"

                when (state.status) {
                    UpdateStatus.READY -> {
                        updateSize.text = "Загрузка завершена. Проверка файла..."

                        val error = withContext(Dispatchers.IO) {
                            UpdateManager.installPendingIfReady(this@ChatsActivity)
                        }

                        if (error != null) {
                            updateSize.text = error
                        }

                        break
                    }

                    UpdateStatus.ERROR -> {
                        updateSize.text = "Ошибка загрузки"
                        break
                    }

                    UpdateStatus.PAUSED -> {
                        updateSize.text = "Загрузка приостановлена"
                    }

                    UpdateStatus.PENDING -> {
                        updateSize.text = "Ожидание загрузки..."
                    }

                    UpdateStatus.PROGRESS -> {
                        updateSize.text = "Загрузка обновления..."
                    }

                    else -> {
                        updateSize.text = "Получение состояния загрузки..."
                    }
                }

                delay(500)
            }
        }
    }

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        startActivity(intent)
    }


    private fun openCopyKeySheet() {
        val bottomSheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(
            R.layout.bottom_sheet_copy_key,
            null
        )
        bottomSheet.setContentView(view)

        val qrImage = view.findViewById<ImageView>(R.id.iv_sheetCopyKey)
        val subMatrixText = view.findViewById<TextView>(R.id.tv_sheetCopyKey)

        val publicKey = KeyManager.getPublicKey(this)
        val publicKeyBase64 = Base64.encodeToString(
            publicKey,
            Base64.NO_WRAP
        )

        subMatrixText.text = "QR-код публичного ключа"

        val data = QrData.Text(publicKeyBase64)

        val primaryColor = ContextCompat.getColor(this, R.color.primary)
        val darkerColor = ColorUtils.blendARGB(
            primaryColor,
            Color.BLACK,
            0.18f
        )

        val options = createQrVectorOptions {
            padding = 0.15f

            background {
                drawable = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = 42.toFloat()
                }
            }

            logo {
                drawable = ContextCompat.getDrawable(
                    this@ChatsActivity,
                    R.drawable.prisma_logo
                )

                size = 0.20f
                padding = QrVectorLogoPadding.Natural(0.2f)
                shape = QrVectorLogoShape.Circle
            }

            colors {
                dark = QrVectorColor.LinearGradient(
                    colors = listOf(
                        0f to darkerColor,
                        1f to primaryColor
                    ),
                    orientation = QrVectorColor.LinearGradient.Orientation.LeftDiagonal
                )

                ball = QrVectorColor.Solid(primaryColor)
                frame = QrVectorColor.Solid(primaryColor)
            }

            shapes {
                darkPixel = QrVectorPixelShape.RoundCorners(0.20f)
                ball = QrVectorBallShape.RoundCorners(0.25f)
                frame = QrVectorFrameShape.RoundCorners(0.20f)
            }
        }

        val qrDrawable = QrCodeDrawable(data, options)
        qrImage.setImageDrawable(qrDrawable)

        view.findViewById<MaterialButton>(R.id.btn_sheetCopyKey)
            .setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE)
                        as ClipboardManager

                val clip = ClipData.newPlainText(
                    "Мой публичный ключ",
                    publicKeyBase64
                )

                clipboard.setPrimaryClip(clip)
                bottomSheet.dismiss()
            }

        bottomSheet.show()
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