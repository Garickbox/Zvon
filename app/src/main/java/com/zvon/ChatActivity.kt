package com.zvon

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mesh.core.Message
import com.mesh.core.MeshNode
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class ChatActivity : AppCompatActivity() {

    companion object {
        private var currentNode: MeshNode? = null
        fun setNode(n: MeshNode) { currentNode = n }
        const val EXTRA_PEER_ID = "peer_id"
        const val EXTRA_PEER_IP = "peer_ip"
        const val EXTRA_PEER_NAME = "peer_name"
    }

    private lateinit var mesh: MeshNode
    private lateinit var peerId: String
    private lateinit var peerIp: String
    private lateinit var peerName: String
    private lateinit var messagesView: LinearLayout
    private lateinit var input: EditText
    private lateinit var scroll: ScrollView
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.US)

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            res.data?.data?.let { uri ->
                mesh.sendFile(uri, peerIp, peerId)
                Toast.makeText(this, "Отправляю файл…", Toast.LENGTH_SHORT).show()
                reload()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mesh = currentNode ?: run { finish(); return }
        peerId = intent.getStringExtra(EXTRA_PEER_ID) ?: run { finish(); return }
        peerIp = intent.getStringExtra(EXTRA_PEER_IP) ?: run { finish(); return }
        peerName = intent.getStringExtra(EXTRA_PEER_NAME) ?: peerId.take(8)

        setContentView(buildUi())
        reload()
    }

    private fun buildUi(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0D0D0D.toInt())
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 32, 24, 16)
            setBackgroundColor(0xFF1A1A1A.toInt())
        }
        header.addView(TextView(this).apply {
            text = "💬 $peerName"
            setTextColor(0xFFFFFFFF.toInt()); textSize = 18f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(Button(this).apply {
            text = "📎"
            setOnClickListener {
                val i = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
                filePicker.launch(i)
            }
        })
        root.addView(header)

        scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        messagesView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 8)
        }
        scroll.addView(messagesView)
        root.addView(scroll)

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 8, 16, 16)
        }
        input = EditText(this).apply {
            hint = "Сообщение…"; setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF666666.toInt())
            maxLines = 4
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        inputRow.addView(input)
        inputRow.addView(Button(this).apply {
            text = "➤"
            setOnClickListener { send() }
        })
        root.addView(inputRow)

        return root
    }

    private fun send() {
        val txt = input.text.toString().trim()
        if (txt.isEmpty()) return
        mesh.sendText(peerId, peerIp, txt)
        input.setText("")
        reload()
    }

    private fun reload() {
        messagesView.removeAllViews()
        for (m in mesh.chatStore.history(peerId)) addBubble(m)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun addBubble(m: Message) {
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 12)
            setBackgroundColor(if (m.fromMe) 0xFF2C5F2D.toInt() else 0xFF2A2A2A.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = if (m.fromMe) Gravity.END else Gravity.START
                setMargins(8, 4, 8, 4)
            }
        }
        val body = if (m.kind == "file")
            "📎 ${m.body}${m.filePath?.let { "\n✓ " + java.io.File(it).name } ?: ""}"
        else m.body
        bubble.addView(TextView(this).apply {
            text = body; setTextColor(0xFFFFFFFF.toInt()); textSize = 14f
        })
        bubble.addView(TextView(this).apply {
            text = timeFmt.format(Date(m.tsMs)); setTextColor(0xFF888888.toInt()); textSize = 10f
            gravity = Gravity.END
        })
        messagesView.addView(bubble)
    }
}
