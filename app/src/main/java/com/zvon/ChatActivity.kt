package com.zvon

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.mesh.core.Message
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class ChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PEER_ID = "peer_id"
        const val EXTRA_PEER_IP = "peer_ip"
        const val EXTRA_PEER_NAME = "peer_name"
    }

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
                ZvonController.meshNode?.sendFile(uri, peerIp, peerId)
                Toast.makeText(this, "Отправляю файл…", Toast.LENGTH_SHORT).show()
                reload()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        peerId = intent.getStringExtra(EXTRA_PEER_ID) ?: run { finish(); return }
        peerIp = intent.getStringExtra(EXTRA_PEER_IP) ?: ""
        peerName = ZvonController.meshNode?.contactsStore?.get(peerId)?.name
            ?.takeIf { it.isNotBlank() && it != peerId }
            ?: intent.getStringExtra(EXTRA_PEER_NAME)
            ?: peerId.take(8)
        InAppLogger.write('I', "Chat", "opened peer=$peerId name=$peerName")

        setContentView(R.layout.activity_chat)
        scroll = findViewById(R.id.scroll)
        messagesView = findViewById(R.id.messagesView)
        input = findViewById(R.id.input)

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            title = "💬 $peerName"
            setNavigationOnClickListener { finish() }
            inflateMenu(R.menu.chat_menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_call -> {
                        if (peerIp.isNotBlank()) {
                            ZvonController.meshNode?.call(peerIp)
                            startActivity(Intent(this@ChatActivity, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            })
                            finish()
                        } else Toast.makeText(this@ChatActivity, "Нет соединения", Toast.LENGTH_SHORT).show()
                        true
                    }
                    else -> false
                }
            }
        }

        findViewById<MaterialButton>(R.id.btnAttach).setOnClickListener {
            val i = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
            filePicker.launch(i)
        }
        findViewById<MaterialButton>(R.id.btnSend).setOnClickListener { send() }

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
        ZvonController.meshNode?.sendText(peerId, peerIp, txt)
        input.setText("")
        reload()
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun reload() {
        messagesView.removeAllViews()
        val mesh = ZvonController.meshNode ?: return
        for (m in mesh.chatStore.history(peerId)) addBubble(m)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun addBubble(m: Message) {
        val bubble = MaterialCardView(this).apply {
            setCardBackgroundColor(if (m.fromMe) 0xFF2B5278.toInt() else 0xFF182533.toInt())
            radius = 16f * resources.displayMetrics.density
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = if (m.fromMe) Gravity.END else Gravity.START
                setMargins(8, 4, 8, 4)
            }
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 12)
        }
        val body = if (m.kind == "file")
            "📎 ${m.body}${m.filePath?.let { "\n✓ " + java.io.File(it).name } ?: ""}"
        else m.body
        inner.addView(TextView(this).apply {
            text = body
            setTextColor(0xFFE1E9F0.toInt())
            textSize = 14f
        })
        inner.addView(TextView(this).apply {
            text = timeFmt.format(Date(m.tsMs)) + if (m.fromMe) " ✓✓" else ""
            setTextColor(0xFF708499.toInt())
            textSize = 10f
            gravity = Gravity.END
        })
        bubble.addView(inner)
        messagesView.addView(bubble)
    }
}
