package com.zvon

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class SelfTestActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var summary: TextView
    private lateinit var results: MutableList<TestResult>
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_selftest)

        findViewById<MaterialToolbar>(R.id.testToolbar).setNavigationOnClickListener { finish() }
        list = findViewById(R.id.testList)
        summary = findViewById(R.id.testSummary)
        results = mutableListOf()

        findViewById<MaterialButton>(R.id.btnRerun).setOnClickListener { runTests() }
        findViewById<MaterialButton>(R.id.btnShareTest).setOnClickListener { shareReport() }

        runTests()
    }

    private fun runTests() {
        list.removeAllViews()
        results.clear()
        summary.text = "Проверяем…"

        lifecycleScope.launch {
            val runner = SelfTestRunner(this@SelfTestActivity)
            val all = runner.runAll { r ->
                runOnUiThread {
                    results.add(r)
                    addRow(r)
                }
            }
            val ok = all.count { it.ok }
            val fail = all.size - ok
            summary.text = if (fail == 0) "✅ Все $ok проверок пройдены"
                            else "⚠️ $ok OK, $fail ошибок"
            summary.setTextColor(if (fail == 0) 0xFF4FCA52.toInt() else 0xFFE53935.toInt())
        }
    }

    private fun addRow(r: TestResult) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            setBackgroundColor(0xFF17212B.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 6) }
        }
        row.addView(TextView(this).apply {
            text = "${if (r.ok) "✅" else "❌"} ${r.name}"
            setTextColor(if (r.ok) 0xFFE1E9F0.toInt() else 0xFFE53935.toInt())
            textSize = 14f
        })
        row.addView(TextView(this).apply {
            text = r.detail
            setTextColor(0xFF708499.toInt())
            textSize = 12f
        })
        list.addView(row)
    }

    private fun shareReport() {
        if (results.isEmpty()) { Toast.makeText(this, "Нет результатов", Toast.LENGTH_SHORT).show(); return }
        val sb = StringBuilder()
        sb.appendLine("=== Zvon Self-Test ${timeFmt.format(Date())} ===")
        for (r in results) {
            sb.appendLine("${if (r.ok) "OK" else "FAIL"} | ${r.name} | ${r.detail}")
        }
        val ok = results.count { it.ok }
        sb.appendLine("Итого: $ok/${results.size} пройдено")
        val f = File(cacheDir, "selftest_${System.currentTimeMillis()}.txt")
        f.writeText(sb.toString())
        try {
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Поделиться отчётом"))
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
