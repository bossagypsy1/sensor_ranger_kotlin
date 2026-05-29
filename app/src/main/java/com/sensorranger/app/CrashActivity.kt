package com.sensorranger.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider

class CrashActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CRASH = "crash"
        const val EXTRA_TITLE = "title"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "App crashed"
        val crash = intent.getStringExtra(EXTRA_CRASH)
            ?: LogManager.getLastCrash()
            ?: "No crash details available."

        // Build UI programmatically — avoids any layout inflation that could re-crash
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(0xFF0A0A0F.toInt())
            setPadding(24, 48, 24, 24)
        }

        root.addView(TextView(this).apply {
            text = "💥 Sensor Ranger crashed"
            setTextColor(0xFFF44336.toInt())
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        })

        root.addView(TextView(this).apply {
            text = title
            setTextColor(0xFFE0E0F0.toInt())
            textSize = 13f
            setPadding(0, 0, 0, 16)
        })

        val scrollView = ScrollView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        scrollView.addView(TextView(this).apply {
            text = crash
            setTextColor(0xFF8888AA.toInt())
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            isTextSelectable = true
            setPadding(0, 0, 0, 16)
        })

        root.addView(scrollView)

        // Button row
        val btnRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
        }

        btnRow.addView(Button(this).apply {
            text = "Copy"
            setOnClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Crash Log", crash))
                Toast.makeText(this@CrashActivity, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = 8 }
        })

        btnRow.addView(Button(this).apply {
            text = "Share"
            setOnClickListener { shareCrashFile() }
            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = 8 }
        })

        btnRow.addView(Button(this).apply {
            text = "Clear & Restart"
            setOnClickListener {
                LogManager.clearCrash()
                LogManager.clearLog()
                val intent = Intent(this@CrashActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        root.addView(btnRow)
        setContentView(root)
    }

    private fun shareCrashFile() {
        try {
            val file = LogManager.crashFile()
            if (!file.exists()) {
                Toast.makeText(this, "No crash file found", Toast.LENGTH_SHORT).show()
                return
            }
            val uri = FileProvider.getUriForFile(
                this, "${packageName}.provider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Sensor Ranger crash log")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share crash log"))
        } catch (e: Exception) {
            Toast.makeText(this, "Share failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
