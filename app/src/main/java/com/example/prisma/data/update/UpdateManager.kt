package com.example.prisma.data.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import com.example.prisma.data.KeyManager.KEYS_FILENAME
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import androidx.core.net.toUri
import androidx.transition.Visibility
import java.security.MessageDigest

object UpdateEndpoints {
    const val metaUpdateInfo = "update"
    const val getApkUpdate = "apk/"

    fun setEndpointToBasePath(endpoint: String?): String {
        val url = "https://prisma.plurio.biz/"
        if (endpoint == null) { return url }

        return url + endpoint
    }
}

object UpdateStore {
    const val UPDATE_SKIPPED_FILENAME = "updater_skipped"
    const val SKIPPED_VERSION = "skipped_version"
    const val DOWNLOAD_ID = "download_id"
    const val DOWNLOAD_SHA256 = "download_sha256"
    const val DOWNLOAD_VERSION_CODE = "download_version_code"
}

enum class UpdateMode {
    NONE, OPTIONAL, FORCED
}

enum class UpdateStatus {
    READY,
    PROGRESS,
    ERROR,
    PENDING,
    PAUSED,
    UNDEF
}

data class UpdateState(
    val status: UpdateStatus,
    val progress: Int,
    val downloaded: Long = 0,
    val total: Long = 0
)

data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val minVersionCode: Long,
    val apkUrl: String,
    val sha256: String,
    val changeLog: String
)

object UpdateManager {
    private val client = OkHttpClient()

    private fun getPrefs(context: Context): SharedPreferences
    {
        return context.getSharedPreferences(UpdateStore.UPDATE_SKIPPED_FILENAME,
            Context.MODE_PRIVATE)
    }

    suspend fun fetchUpdateInfo(): UpdateInfo? {

        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(UpdateEndpoints.setEndpointToBasePath(UpdateEndpoints.metaUpdateInfo))
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) { return@withContext null }

                    try {
                        val body = response.body?.string()
                        val json = JSONObject(body)

                        val result = UpdateInfo(
                            versionCode = json.getLong("version_code"),
                            versionName = json.getString("version_name"),
                            minVersionCode = json.getLong("min_version_code"),
                            apkUrl = json.getString("apk_url"),
                            sha256 = json.getString("sha256"),
                            changeLog = json.getString("change_log")
                        )

                        result
                    } catch (ex: Exception) {
                        Log.d("UpdateManager", "fetchUpdateInfo error: $ex")
                        null
                    }
                }
            } catch (ex: Exception) {
                Log.d("UpdateManager", "fetchUpdateInfo error: $ex")
                null
            }
        }
    }

    fun currentVersionCode(context: Context): Long {
        return PackageInfoCompat.getLongVersionCode(
            context.packageManager.getPackageInfo(context.packageName, 0)
        )
    }

    fun decide(info: UpdateInfo, current: Long): UpdateMode {
        Log.d(
            "UpdateManager",
            "FetchUpdate: fetchVersionCode: ${info.versionCode} | current: $current"
        )
        if (info.versionCode <= current) return UpdateMode.NONE
        if (info.minVersionCode > current) return UpdateMode.FORCED

        return UpdateMode.OPTIONAL
    }

    fun isSkipped(context: Context, code: Long): Boolean {
        val prefs = getPrefs(context)
        val skippedVersion = prefs.getLong(UpdateStore.SKIPPED_VERSION, 0)
        return skippedVersion == code
    }

    fun markSkipped(context: Context, code: Long) {
        getPrefs(context).edit {
            putLong(UpdateStore.SKIPPED_VERSION, code)
        }
    }

    fun startDownload(context: Context, info: UpdateInfo): Long {
        val download = context.getSystemService(DownloadManager::class.java)
        val fileDest = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "prisma-update.apk"
        )

        if (fileDest.exists()) {
            fileDest.delete()
        }

        val request = DownloadManager.Request(info.apkUrl.toUri())
            .setTitle("Обновление Prisma")
            .setDescription("Установка обновления: ${info.versionName}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                "prisma-update.apk"
            )

        val downloadId = download.enqueue(request)

        getPrefs(context).edit {
            putLong(UpdateStore.DOWNLOAD_ID, downloadId)
            putString(UpdateStore.DOWNLOAD_SHA256, info.sha256)
            putLong(UpdateStore.DOWNLOAD_VERSION_CODE, info.versionCode)
        }

        return downloadId
    }

    fun verifySha256(file: File, expected: String): Boolean {
        val sha256 = MessageDigest.getInstance("SHA-256")
        val buff = ByteArray(8192)
        file.inputStream().use { inputStream ->
            var n = inputStream.read(buff)
            while (n != -1) {
                sha256.update(buff, 0, n)
                n = inputStream.read(buff)
            }
        }
        val actual = sha256.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(expected.trim(), ignoreCase = true)
    }

    fun installApk(context: Context, expectedSha256: String): String? {
        if (!context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                ("package:" + context.packageName).toUri()
            )
            context.startActivity(intent)
            return "Разрешите установку из этого приложения и нажмите «Установить» снова"
        }

        val file = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "prisma-update.apk"
        )

        if (!verifySha256(file, expectedSha256)) {
            file.delete()
            return "Файл повреждён, скачайте заново"
        }

        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        return null
    }

    fun getProgress(context: Context, downloadId: Long): UpdateState {
        val dm = context.getSystemService(DownloadManager::class.java)
            ?: return UpdateState(UpdateStatus.UNDEF, 0)

        val cursor = dm.query(
            DownloadManager.Query().setFilterById(downloadId)
        )

        cursor.use {
            if (!it.moveToFirst()) {
                return UpdateState(UpdateStatus.UNDEF, 0)
            }

            val statusIndex = it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val downloadedIndex = it.getColumnIndexOrThrow(
                DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR
            )
            val totalIndex = it.getColumnIndexOrThrow(
                DownloadManager.COLUMN_TOTAL_SIZE_BYTES
            )
            val reasonIndex = it.getColumnIndexOrThrow(
                DownloadManager.COLUMN_REASON
            )

            val status = it.getInt(statusIndex)
            val downloaded = it.getLong(downloadedIndex)
            val total = it.getLong(totalIndex)
            val reason = it.getInt(reasonIndex)

            val progress = if (total > 0) {
                ((downloaded * 100) / total).toInt().coerceIn(0, 100)
            } else {
                0
            }

            val updateStatus = when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> UpdateStatus.READY
                DownloadManager.STATUS_FAILED -> UpdateStatus.ERROR
                DownloadManager.STATUS_PAUSED -> UpdateStatus.PAUSED
                DownloadManager.STATUS_PENDING -> UpdateStatus.PENDING
                DownloadManager.STATUS_RUNNING -> UpdateStatus.PROGRESS
                else -> UpdateStatus.UNDEF
            }

            if (status == DownloadManager.STATUS_FAILED) {
                val error = when (reason) {
                    DownloadManager.ERROR_UNKNOWN -> "UNKNOWN"
                    DownloadManager.ERROR_FILE_ERROR -> "FILE_ERROR"
                    DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "UNHANDLED_HTTP_CODE"
                    DownloadManager.ERROR_HTTP_DATA_ERROR -> "HTTP_DATA_ERROR"
                    DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "TOO_MANY_REDIRECTS"
                    DownloadManager.ERROR_INSUFFICIENT_SPACE -> "INSUFFICIENT_SPACE"
                    DownloadManager.ERROR_DEVICE_NOT_FOUND -> "DEVICE_NOT_FOUND"
                    DownloadManager.ERROR_CANNOT_RESUME -> "CANNOT_RESUME"
                    DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "FILE_ALREADY_EXISTS"
                    else -> "OTHER"
                }

                Log.e(
                    "UpdateManager",
                    "Download failed! Reason: $error (code: $reason)"
                )
            }

            return UpdateState(
                status = updateStatus,
                progress = progress,
                downloaded = downloaded,
                total = total
            )
        }
    }

    fun installPendingIfReady(context: Context): String? {
        val prefs = getPrefs(context)
        val id = prefs.getLong(UpdateStore.DOWNLOAD_ID, -1L)
        if (id == -1L) { return null }
        val sha = prefs.getString(UpdateStore.DOWNLOAD_SHA256, null) ?: return null

        return when(getProgress(context, id).status) {
            UpdateStatus.READY -> {
                if(!context.packageManager.canRequestPackageInstalls()) {
                    return installApk(context, sha)
                }
                val error = installApk(context, sha)
                prefs.edit {
                    remove(UpdateStore.DOWNLOAD_ID)
                }
                error
            }
            UpdateStatus.ERROR -> {
                prefs.edit { remove(UpdateStore.DOWNLOAD_ID) }
                "Не удалось скачать обновление"
            }
            else -> null
        }
    }
}
