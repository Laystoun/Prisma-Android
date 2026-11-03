package com.example.prisma

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.prisma.data.KeyManager
import com.example.prisma.data.PrismaClient
import com.example.prisma.data.update.UpdateBottomSheet
import com.example.prisma.data.update.UpdateManager
import com.example.prisma.data.update.UpdateMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PrismaApp : Application() {
    lateinit var client: PrismaClient

    override fun onCreate() {
        super.onCreate()

        initClient()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (activity !is AppCompatActivity) { return }

                activity.lifecycleScope.launch {
                    val info = UpdateManager.fetchUpdateInfo() ?: return@launch
                    val current = UpdateManager.currentVersionCode(activity)
                    val mode = UpdateManager.decide(info, current)

                    if (mode == UpdateMode.NONE) return@launch
                    if (mode == UpdateMode.FORCED) return@launch
                    if (UpdateManager.isSkipped(activity, info.versionCode)) return@launch

                    val fm = activity.supportFragmentManager
                    if (activity.isFinishing || fm.isStateSaved) return@launch
                    if (fm.findFragmentByTag("update") != null) return@launch

                    val sheet = UpdateBottomSheet()
                    sheet.arguments = Bundle().apply {
                        putString("versionName", info.versionName)
                        putLong("versionCode", info.versionCode)
                        putLong("minVersionCode", info.minVersionCode)
                        putString("apkUrl", info.apkUrl)
                        putString("sha256", info.sha256)
                        putString("changeLog", info.changeLog)
                    }
                    sheet.show(fm, "update")
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    fun initClient() {
        if (!::client.isInitialized && KeyManager.hasKeys(this)) {
            val pubKey = KeyManager.getPublicKey(this)
            client = PrismaClient(
                this,
                pubKey
            )
            client.start()

        }
    }
}