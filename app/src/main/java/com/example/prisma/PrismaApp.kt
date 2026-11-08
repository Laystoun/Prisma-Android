package com.example.prisma

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.example.prisma.data.KeyManager
import com.example.prisma.data.PrismaClient
import com.example.prisma.data.update.UpdateBottomSheet
import com.example.prisma.data.update.UpdateManager
import com.example.prisma.data.update.UpdateMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PrismaApp : Application() {

    lateinit var client: PrismaClient

    private var updateCheckJob: Job? = null

    override fun onCreate() {
        super.onCreate()

        initClient()
        registerUpdateCallbacks()
    }

    private fun registerUpdateCallbacks() {
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {

                override fun onActivityStarted(activity: Activity) {
                    if (activity !is AppCompatActivity ||
                        activity is RequiredUpdateActivity
                    ) {
                        return
                    }

                    scheduleUpdateCheck(activity)
                }

                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?
                ) {}

                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle
                ) {}

                override fun onActivityDestroyed(activity: Activity) {}
            }
        )
    }

    private fun scheduleUpdateCheck(activity: AppCompatActivity) {
        updateCheckJob?.cancel()

        updateCheckJob = activity.lifecycleScope.launch {
            checkForUpdates(activity)
        }
    }

    private suspend fun checkForUpdates(activity: AppCompatActivity) {
        val info = UpdateManager.fetchUpdateInfo() ?: return

        val currentVersion = UpdateManager.currentVersionCode(activity)
        val updateMode = UpdateManager.decide(info, currentVersion)

        when (updateMode) {
            UpdateMode.NONE -> return

            UpdateMode.FORCED -> {
                launchRequiredUpdate(activity, info)
            }

            UpdateMode.OPTIONAL -> {
                showOptionalUpdate(activity, info)
            }
        }
    }

    private fun launchRequiredUpdate(
        activity: AppCompatActivity,
        info: com.example.prisma.data.update.UpdateInfo
    ) {
        if (activity.isFinishing ||
            activity.isDestroyed ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) {
            return
        }

        val intent = Intent(
            activity,
            RequiredUpdateActivity::class.java
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK

            putExtra(RequiredUpdateActivity.EXTRA_VERSION_NAME, info.versionName)
            putExtra(RequiredUpdateActivity.EXTRA_VERSION_CODE, info.versionCode)
            putExtra(RequiredUpdateActivity.EXTRA_MIN_VERSION_CODE, info.minVersionCode)
            putExtra(RequiredUpdateActivity.EXTRA_APK_URL, info.apkUrl)
            putExtra(RequiredUpdateActivity.EXTRA_SHA256, info.sha256)
            putExtra(RequiredUpdateActivity.EXTRA_CHANGE_LOG, info.changeLog)
        }

        activity.startActivity(intent)
    }

    private fun showOptionalUpdate(
        activity: AppCompatActivity,
        info: com.example.prisma.data.update.UpdateInfo
    ) {
        if (UpdateManager.isSkipped(activity, info.versionCode)) {
            return
        }

        val fragmentManager = activity.supportFragmentManager

        if (activity.isFinishing ||
            activity.isDestroyed ||
            fragmentManager.isStateSaved
        ) {
            return
        }

        if (fragmentManager.findFragmentByTag(UPDATE_TAG) != null) {
            return
        }

        val sheet = UpdateBottomSheet().apply {
            arguments = Bundle().apply {
                putString("versionName", info.versionName)
                putLong("versionCode", info.versionCode)
                putLong("minVersionCode", info.minVersionCode)
                putString("apkUrl", info.apkUrl)
                putString("sha256", info.sha256)
                putString("changeLog", info.changeLog)
            }
        }

        sheet.show(fragmentManager, UPDATE_TAG)
    }

    fun initClient() {
        if (!::client.isInitialized && KeyManager.hasKeys(this)) {
            val publicKey = KeyManager.getPublicKey(this)

            client = PrismaClient(
                this,
                publicKey
            )

            client.start()
        }
    }

    companion object {
        private const val UPDATE_TAG = "update"
    }
}