package com.zvon

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.mesh.core.InAppLogger
import com.mesh.core.PeerTestRunner
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class PeerTestActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var summary: TextView
    private var lastReport: PeerTestRunner.Report? = null
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_peertest)

        findViewById<MaterialToolbar>(R.id.ptToolbar).setNavigationOnClickListener { finish() }
        list = findViewById(R.id.ptList)
        summary = findViewById(R.id.ptSummary)

        findViewById<MaterialButton>(R.id.btnPtRun).setOnClickListener { runTest() }
        findViewById<MaterialButton>(R.id.btnPtShare).setOnClickListener { shareReport() }
    }

    private fun runTest() {
        val mesh = ZvonController.meshNode
        if (mesh == null) { Toast.makeText(this, "Сервис не запущен", Toast.LENGTH_SHORT).show(); return }
        val peerIp = mesh.currentPeerIp()
        if (peerIp == null) { Toast.makeText(this, "Нет соединения с пиром", Toast.LENGTH_SHORT).show(); return }
        val runner = ZvonController.peerTestRunner
        if (runner == null) { Toast.makeText(this, "Runner не инициализирован", Toast.LENGTH_SHORT).show(); return }

        list.removeAllViews()
        summary.text = "Запуск…"

        // Используем ТОТ ЖЕ runner, что принимает onTestPacket — иначе accept/invite уходят в разные экземпляры
        runner.onProgress = { progress -> runOnUiThread { addRow("• $progress") } }

        lifecycleScope.launch {
            val report = runner.runAsMaster(peerIp)
            lastReport = report
            runOnUiThread { showReport(report) }
        }
    }

    private fun addRow(text: String) {
        list.addView(TextView(this).apply {
            this.text = text
            setTextColor(0xFFE1E9F0.toInt()); textSize = 13f
            setPadding(16, 6, 16, 6)
        })
    }

    private fun showReport(r: PeerTestRunner.Report) {
        list.removeAllViews()
        if (!r.success) {
            summary.text = "❌ Провал: ${r.error}"
            summary.setTextColor(0xFFE53935.toInt())
            addRow("❌ ${r.error}")
            return
        }
        summary.text = "✅ Тест пройден"
        summary.setTextColor(0xFF4FCA52.toInt())
        addRow("✅ РУКОПОЖАТИЕ")
        addRow("   RTT min=${r.rttMin}мс avg=${r.rttAvg}мс max=${r.rttMax}мс")
        addRow("")
        val lossPct = (100 - r.voiceReceived) * 100.0 / 100
        addRow("🎤 ГОЛОС")
        addRow("   ${r.voiceReceived}/100 пакетов, потери ${String.format(Locale.US, "%.1f", lossPct)}%")
        addRow("")
        addRow("💬 ТЕКСТ")
        addRow("   ${r.textReceived}/10 сообщений, дублей ${r.textDups}, порядок ${if (r.textInOrder) "✓" else "✗"}")
    }

    private fun shareReport() {
        val r = lastReport ?: run {
            Toast.makeText(this, "Нет отчёта", Toast.LENGTH_SHORT).show()
            return
        }
        val sb = StringBuilder()
        sb.appendLine("=== Zvon Peer Test ${timeFmt.format(Date())} ===")
        sb.appendLine("success=${r.success} err=${r.error ?: "-"}")
        sb.appendLine("RTT: min=${r.rttMin} avg=${r.rttAvg} max=${r.rttMax}")
        sb.appendLine("Voice: ${r.voiceReceived}/${r.voiceSent}")
        sb.appendLine("Text: ${r.textReceived}/${r.textSent} dups=${r.textDups} inOrder=${r.textInOrder}")
        val f = File(cacheDir, "peertest_${System.currentTimeMillis()}.txt")
        f.writeText(sb.toString())
        try {
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Поделиться"))
        } catch (e: Exception) { Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show() }
    }
}
