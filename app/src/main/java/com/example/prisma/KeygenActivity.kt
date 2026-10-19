package com.example.prisma

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.GridLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView
import java.security.MessageDigest
import kotlin.random.Random

class KeygenActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_keygen)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        val subMatrixText = findViewById<TextView>(R.id.tv_keygen_keyText)
        val grid = findViewById<GridLayout>(R.id.grid_keygen_fingerprint)

        val fakeKey = Random.nextBytes(32)
        val hash = MessageDigest.getInstance("SHA-256").digest(fakeKey)

        var text = ""

        repeat(8) { i ->
            val b1 = hash[i * 2]
            val b2 = hash[i * 2 + 1]
            text += "%02X%02X".format(b1, b2)

            if (i == 3) {
                text += "\n"
            } else if (i != 7) {
                text += " "
            }
        }

        

        subMatrixText.text = text

        repeat(25) { idx ->
            val row = idx / 5
            var col = idx % 5
            if (col > 2) col = 4 - col

            val byte = hash[row * 3 + col]
            val state = (byte.toInt() and 0xFF) % 3

            val cell = View(this)
            cell.setBackgroundResource(R.drawable.shape_fingerprint_cell)

            val color = when (state) {
                0 -> ContextCompat.getColor(this, R.color.fingerprint_empty)// colorEmpty
                1 -> ContextCompat.getColor(this, R.color.fingerprint_soft) // colorSoft
                else -> ContextCompat.getColor(this, R.color.fingerprint_full)// colorFull
            }
            cell.backgroundTintList = ColorStateList.valueOf(color)

            val cellParams = GridLayout.LayoutParams().apply {
                width=resources.getDimensionPixelSize(R.dimen.fingerprint_cell_size)
                height=resources.getDimensionPixelSize(R.dimen.fingerprint_cell_size)
                setMargins(
                    resources.getDimensionPixelSize(R.dimen.fingerprint_cell_margin),
                    resources.getDimensionPixelSize(R.dimen.fingerprint_cell_margin),
                    resources.getDimensionPixelSize(R.dimen.fingerprint_cell_margin),
                    resources.getDimensionPixelSize(R.dimen.fingerprint_cell_margin)
                )
            }

            grid.addView(cell, cellParams)
        }
    }
}