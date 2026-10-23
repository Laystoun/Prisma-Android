package com.example.prisma

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.prisma.data.KeyManager

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        val chatsActivity = Intent(this, ChatsActivity::class.java)

        if (KeyManager.hasKeys(this)) {
            startActivity(chatsActivity)
            finish()
            return
        }

        val continueBtn = findViewById<Button>(R.id.btn_onboard_keygen)

        continueBtn.setOnClickListener { btn ->
            val keygenActivity = Intent(this, KeygenActivity::class.java)
            KeyManager.generateAndSave(this)
            startActivity(keygenActivity)
            finish()
        }
    }
}