package com.zvon

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class FilesActivity : AppCompatActivity() {

    private val dateFmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_files)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.filesToolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val list = findViewById<LinearLayout>(R.id.filesList)
        val empty = findViewById<TextView>(R.id.filesEmpty)

        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Zvon")
        val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()

        if (files.isEmpty()) {
            empty.visibility = View.VISIBLE
            return
        }

        for (f in files) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 16, 16, 16)
                setBackgroundColor(0xFF17212B.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 8) }
            }
            row.addView(TextView(this).apply {
                text = "📄 ${f.name}"
                setTextColor(0xFFE1E9F0.toInt())
                textSize = 15f
            })
            row.addView(TextView(this).apply {
                text = "${formatSize(f.length())} · ${dateFmt.format(Date(f.lastModified()))}"
                setTextColor(0xFF708499.toInt())
                textSize = 12f
            })
            row.setOnClickListener { openFile(f) }
            list.addView(row)
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes Б"
        bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
        else -> String.format(Locale.US, "%.1f МБ", bytes / 1024.0 / 1024.0)
    }

    private fun openFile(f: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            Toast.makeText(this, "Не могу открыть: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
