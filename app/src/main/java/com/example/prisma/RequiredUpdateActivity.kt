package com.example.prisma

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.prisma.data.update.UpdateInfo
import com.example.prisma.data.update.UpdateManager
import com.example.prisma.data.update.UpdateMode
import com.example.prisma.data.update.UpdateState
import com.example.prisma.data.update.UpdateStatus
import com.example.prisma.data.update.UpdateStore
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class RequiredUpdateActivity : AppCompatActivity() {

    private lateinit var currentVersion: TextView
    private lateinit var currentCode: TextView

    private lateinit var targetVersion: TextView
    private lateinit var targetCode: TextView

    private lateinit var changelog: TextView
    private lateinit var statusText: TextView

    private lateinit var progress: LinearProgressIndicator
    private lateinit var downloadButton: MaterialButton

    private var updateInfo: UpdateInfo? = null
    private var downloadJob: Job? = null

    private enum class ButtonAction {
        DOWNLOAD,
        INSTALL
    }

    private var buttonAction = ButtonAction.DOWNLOAD


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContentView(R.layout.activity_required_update)

        ViewCompat.setOnApplyWindowInsetsListener(
            findViewById(R.id.requiredUpdateRoot)
        ) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                bars.left,
                bars.top,
                bars.right,
                bars.bottom
            )
            insets
        }
        bindViews()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                }
            }
        )

        updateInfo = readUpdateInfo()

        val info = updateInfo

        if (info == null) {
            statusText.visibility = View.VISIBLE
            statusText.text =
                "Не удалось получить информацию об обновлении. Перезапустите приложение."

            downloadButton.isEnabled = false
            return
        }

        showUpdateInfo(info)

        downloadButton.setOnClickListener {
            when (buttonAction) {
                ButtonAction.DOWNLOAD -> startOrResumeDownload()
                ButtonAction.INSTALL -> installReadyDownload()
            }
        }
    }


    override fun onStart() {
        super.onStart()

        resumeUpdateFlow()
    }


    private fun bindViews() {
        currentVersion = findViewById(
            R.id.tv_requiredUpdate_currentVersion
        )

        currentCode = findViewById(
            R.id.tv_requiredUpdate_currentCode
        )

        targetVersion = findViewById(
            R.id.tv_requiredUpdate_targetVersion
        )

        targetCode = findViewById(
            R.id.tv_requiredUpdate_targetCode
        )

        changelog = findViewById(
            R.id.tv_requiredUpdate_changelog
        )

        statusText = findViewById(
            R.id.tv_requiredUpdate_status
        )

        progress = findViewById(
            R.id.progress_requiredUpdate
        )

        downloadButton = findViewById(
            R.id.btn_requiredUpdate_download
        )
    }

    private fun readUpdateInfo(): UpdateInfo? {

        val versionName = intent.getStringExtra(EXTRA_VERSION_NAME)
            ?: return null

        val apkUrl = intent.getStringExtra(EXTRA_APK_URL)
            ?: return null

        val sha256 = intent.getStringExtra(EXTRA_SHA256)
            ?: return null

        val versionCode = intent.getLongExtra(
            EXTRA_VERSION_CODE,
            -1L
        )

        val minVersionCode = intent.getLongExtra(
            EXTRA_MIN_VERSION_CODE,
            -1L
        )

        if (versionCode <= 0 || minVersionCode <= 0) {
            return null
        }

        return UpdateInfo(
            versionCode = versionCode,
            versionName = versionName,
            minVersionCode = minVersionCode,
            apkUrl = apkUrl,
            sha256 = sha256,
            changeLog = intent.getStringExtra(EXTRA_CHANGE_LOG).orEmpty()
        )
    }

    private fun showUpdateInfo(info: UpdateInfo) {

        val packageInfo = packageManager.getPackageInfo(
            packageName,
            0
        )

        val installedCode = PackageInfoCompat.getLongVersionCode(
            packageInfo
        )

        currentVersion.text = packageInfo.versionName ?: "Неизвестно"
        currentCode.text = "Сборка $installedCode"

        targetVersion.text = info.versionName
        targetCode.text = "Сборка ${info.versionCode}"

        changelog.text = info.changeLog.ifBlank {
            "Описание изменений не предоставлено."
        }
    }

    private fun resumeUpdateFlow() {
        val info = updateInfo ?: return
        if (downloadJob?.isActive == true) return

        lifecycleScope.launch {
            val installedCode = withContext(Dispatchers.IO) {
                UpdateManager.currentVersionCode(this@RequiredUpdateActivity)
            }

            if (installedCode >= info.minVersionCode) {
                openMainActivity()
                return@launch
            }

            val downloadId = getSavedDownloadId(info)

            if (downloadId == null) {
                showDownloadAction()
                return@launch
            }

            val state = withContext(Dispatchers.IO) {
                UpdateManager.getProgress(
                    this@RequiredUpdateActivity,
                    downloadId
                )
            }

            when (state.status) {

                UpdateStatus.READY -> {
                    handleDownloadReady()
                }

                UpdateStatus.PROGRESS,
                UpdateStatus.PENDING,
                UpdateStatus.PAUSED -> {
                    observeDownload(downloadId)
                }

                UpdateStatus.ERROR,
                UpdateStatus.UNDEF -> {
                    showAction(
                        "Не удалось продолжить загрузку. Попробуйте скачать обновление ещё раз.",
                        "Повторить загрузку",
                        ButtonAction.DOWNLOAD
                    )
                }
            }
        }
    }

    private fun getSavedDownloadId(info: UpdateInfo): Long? {

        val prefs = getSharedPreferences(
            UpdateStore.UPDATE_SKIPPED_FILENAME,
            Context.MODE_PRIVATE
        )

        val savedVersion = prefs.getLong(
            UpdateStore.DOWNLOAD_VERSION_CODE,
            -1L
        )

        if (savedVersion != info.versionCode) {
            return null
        }

        val id = prefs.getLong(
            UpdateStore.DOWNLOAD_ID,
            -1L
        )

        return id.takeIf { it > 0L }
    }

    private fun startOrResumeDownload() {

        val info = updateInfo ?: return

        lifecycleScope.launch {

            val existingId = getSavedDownloadId(info)

            if (existingId != null) {

                val state = withContext(Dispatchers.IO) {
                    UpdateManager.getProgress(
                        this@RequiredUpdateActivity,
                        existingId
                    )
                }

                when (state.status) {

                    UpdateStatus.READY -> {
                        handleDownloadReady()
                        return@launch
                    }

                    UpdateStatus.PROGRESS,
                    UpdateStatus.PENDING,
                    UpdateStatus.PAUSED -> {
                        observeDownload(existingId)
                        return@launch
                    }

                    else -> Unit
                }
            }

            setDownloading("Подготовка загрузки...")

            try {

                val downloadId = withContext(Dispatchers.IO) {
                    UpdateManager.startDownload(
                        this@RequiredUpdateActivity,
                        info
                    )
                }

                observeDownload(downloadId)

            } catch (ex: Exception) {

                showAction(
                    "Не удалось начать загрузку. Проверьте подключение к интернету.",
                    "Повторить загрузку",
                    ButtonAction.DOWNLOAD
                )
            }
        }
    }

    private fun observeDownload(downloadId: Long) {
        if (downloadJob?.isActive == true) return

        downloadJob = lifecycleScope.launch {
            setDownloading("Загрузка обновления...")
            while (isActive) {
                val installedCode = withContext(Dispatchers.IO) {
                    UpdateManager.currentVersionCode(this@RequiredUpdateActivity)
                }
                if (installedCode >= updateInfo!!.minVersionCode) {
                    openMainActivity()
                    return@launch
                }

                val state = withContext(Dispatchers.IO) {
                    UpdateManager.getProgress(
                        this@RequiredUpdateActivity,
                        downloadId
                    )
                }

                when (state.status) {

                    UpdateStatus.READY -> {
                        handleDownloadReady()
                        return@launch
                    }

                    UpdateStatus.ERROR -> {
                        showAction(
                            "Ошибка загрузки. Попробуйте скачать обновление ещё раз.",
                            "Повторить загрузку",
                            ButtonAction.DOWNLOAD
                        )
                        return@launch
                    }

                    UpdateStatus.PROGRESS -> {
                        showProgress(state)
                    }

                    UpdateStatus.PENDING -> {
                        setDownloading("Ожидание загрузки...")
                    }

                    UpdateStatus.PAUSED -> {
                        setDownloading("Загрузка приостановлена...")
                    }

                    UpdateStatus.UNDEF -> {
                        showAction(
                            "Не удалось определить состояние загрузки.",
                            "Повторить загрузку",
                            ButtonAction.DOWNLOAD
                        )
                        return@launch
                    }
                }

                delay(500)
            }
        }
    }

    private suspend fun handleDownloadReady() {

        progress.visibility = View.GONE

        if (!packageManager.canRequestPackageInstalls()) {

            showAction(
                "Загрузка завершена. Разрешите установку приложений из Prisma, чтобы продолжить.",
                "Продолжить установку",
                ButtonAction.INSTALL
            )

            return
        }

        statusText.visibility = View.VISIBLE
        statusText.text = "Загрузка завершена. Подготовка установщика..."

        val error = withContext(Dispatchers.IO) {
            UpdateManager.installPendingIfReady(
                this@RequiredUpdateActivity
            )
        }

        if (error != null) {

            val action = if (!packageManager.canRequestPackageInstalls()) {
                ButtonAction.INSTALL
            } else {
                ButtonAction.DOWNLOAD
            }

            showAction(
                error,
                if (action == ButtonAction.INSTALL) {
                    "Продолжить установку"
                } else {
                    "Скачать повторно"
                },
                action
            )

        } else {

            statusText.visibility = View.VISIBLE
            statusText.text =
                "Подтвердите установку обновления в системном окне Android."

            downloadButton.isEnabled = false
            downloadButton.text = "Ожидание установки..."
        }
    }

    private fun installReadyDownload() {

        lifecycleScope.launch {

            val error = withContext(Dispatchers.IO) {
                UpdateManager.installPendingIfReady(
                    this@RequiredUpdateActivity
                )
            }

            if (error != null) {

                val permissionMissing =
                    !packageManager.canRequestPackageInstalls()

                showAction(
                    error,
                    if (permissionMissing) {
                        "Продолжить установку"
                    } else {
                        "Скачать повторно"
                    },
                    if (permissionMissing) {
                        ButtonAction.INSTALL
                    } else {
                        ButtonAction.DOWNLOAD
                    }
                )

            } else {

                statusText.visibility = View.VISIBLE
                statusText.text =
                    "Подтвердите установку в системном окне Android."

                downloadButton.isEnabled = false
                downloadButton.text = "Ожидание установки..."
            }
        }
    }

    private fun showProgress(state: UpdateState) {

        progress.visibility = View.VISIBLE
        progress.isIndeterminate = state.total <= 0
        progress.progress = state.progress

        val downloaded = formatSize(state.downloaded)
        val total = formatSize(state.total)

        statusText.visibility = View.VISIBLE
        statusText.text =
            "${state.progress}% · $downloaded из $total"

        downloadButton.isEnabled = false
        downloadButton.text = "Загрузка..."
    }


    private fun setDownloading(message: String) {

        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true

        statusText.visibility = View.VISIBLE
        statusText.text = message

        downloadButton.isEnabled = false
        downloadButton.text = "Загрузка..."
    }

    private fun showDownloadAction() {

        showAction(
            "Обновление ещё не загружено. Установите его, чтобы продолжить.",
            "Скачать обновление",
            ButtonAction.DOWNLOAD
        )
    }


    private fun showAction(
        message: String,
        buttonText: String,
        action: ButtonAction
    ) {

        progress.visibility = View.GONE

        statusText.visibility = View.VISIBLE
        statusText.text = message

        buttonAction = action

        downloadButton.text = buttonText
        downloadButton.isEnabled = true
    }


    private fun formatSize(bytes: Long): String {

        val mb = bytes / 1024.0 / 1024.0

        return String.format(
            Locale.getDefault(),
            "%.1f МБ",
            mb
        )
    }

    private fun openMainActivity() {

        if (isFinishing || isDestroyed) return

        val intent = Intent(
            this,
            MainActivity::class.java
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        startActivity(intent)
        finish()
    }


    companion object {

        const val EXTRA_VERSION_NAME = "versionName"
        const val EXTRA_VERSION_CODE = "versionCode"
        const val EXTRA_MIN_VERSION_CODE = "minVersionCode"
        const val EXTRA_APK_URL = "apkUrl"
        const val EXTRA_SHA256 = "sha256"
        const val EXTRA_CHANGE_LOG = "changeLog"
    }
}