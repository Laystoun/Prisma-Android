package com.example.prisma.data.update

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import com.example.prisma.ChatsActivity
import com.example.prisma.R
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton

class UpdateBottomSheet : BottomSheetDialogFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(
            R.layout.bottom_sheet_new_update,
            container,
            false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val versionName = arguments?.getString("versionName")
        val versionCode = arguments?.getLong("versionCode")
        val minVersionCode = arguments?.getLong("minVersionCode")
        val apkUrl = arguments?.getString("apkUrl")
        val sha256 = arguments?.getString("sha256")
        val changeLog = arguments?.getString("changeLog")

        /*view elms*/
        val textNewVersion = view.findViewById<TextView>(R.id.tv_sheetNewUpdate_version)
        val textChangeLog = view.findViewById<TextView>(R.id.tv_sheetNewUpdate_description)
        val btnGetNewUpdate = view.findViewById<MaterialButton>(R.id.btn_sheetNewUpdate_getUpdate)
        val skippedUpdate = view.findViewById<FrameLayout>(R.id.fl_sheetNewUpdate_skipped)

        val updateInfo = UpdateInfo(
            versionCode = versionCode!!,
            versionName = versionName!!,
            minVersionCode = minVersionCode!!,
            apkUrl = apkUrl!!,
            sha256 = sha256!!,
            changeLog = changeLog!!
        )

        skippedUpdate.setOnClickListener {
            UpdateManager.markSkipped(view.context, versionCode)

            dismiss()
        }

        btnGetNewUpdate.setOnClickListener {
            dismiss()

            val downloadId = UpdateManager.startDownload(
                view.context,
                updateInfo
            )

            (activity as? ChatsActivity)?.observeUpdateDownload(downloadId)
        }

        textNewVersion.text = "Новая версия: $versionName"
        textChangeLog.text = "Что нового: $changeLog"
    }
}