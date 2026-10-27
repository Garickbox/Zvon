package com.zvon

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.mesh.core.AudioPipeline
import com.mesh.core.PipelineMode
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private var pipeline: AudioPipeline? = null
    private lateinit var statusText: TextView
    private lateinit var btnLoopback: Button
    private lateinit var btnRaw: Button
    private var isStopping = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        btnLoopback = findViewById(R.id.btnLoopback)
        btnRaw = findViewById(R.id.btnRaw)
        requestPerms()
        btnLoopback.setOnClickListener { togglePipeline(PipelineMode.FULL_LOOPBACK) }
        btnRaw.setOnClickListener { togglePipeline(PipelineMode.RAW_CODEC) }
    }

    private fun togglePipeline(mode: PipelineMode) {
        if (isStopping) return
        val p = pipeline
        if (p == null) {
            pipeline = AudioPipeline(mode)
            pipeline?.start()
            statusText.text = "Running: $mode"
            btnLoopback.isEnabled = false; btnRaw.isEnabled = false
        } else {
            isStopping = true
            btnLoopback.isEnabled = false; btnRaw.isEnabled = false
            lifecycleScope.launch {
                p.stop()
                pipeline = null
                statusText.text = "Stopped"
                btnLoopback.isEnabled = true; btnRaw.isEnabled = true
                isStopping = false
            }
        }
    }

    private fun requestPerms() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 1)
    }
}
