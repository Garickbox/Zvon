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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import com.mesh.core.CallState
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
        peerIp = intent.getStringExtra(EXTRA_PEER_IP) ?: ""
        peerName = intent.getStringExtra(EXTRA_PEER_NAME) ?: peerId.take(8)

        setContentView(R.layout.activity_chat)
        scroll = findViewById(R.id.scroll)
        messagesView = findViewById(R.id.messagesView)
        input = findViewById(R.id.input)

        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).apply {
            title = "💬 $peerName"
            setNavigationOnClickListener { finish() }
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAttach).setOnClickListener {
            val i = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
            filePicker.launch(i)
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSend).setOnClickListener { send() }

        reload()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ZvonController.chatMessages.collect { msg ->
                    if (msg.peerNodeId == peerId) reload()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ZvonController.setActiveChat(peerId)
        reload()
    }

    override fun onPause() {
        super.onPause()
        ZvonController.setActiveChat(null)
    }


    private fun send() {
        val txt = input.text.toString().trim()
        if (txt.isEmpty()) return
        if (peerIp.isBlank()) {
            Toast.makeText(this, "Нет соединения с пиром", Toast.LENGTH_SHORT).show()
            return
        }
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
