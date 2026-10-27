package com.mesh.core

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class AudioCapture(
    private val onPcmFrameReady: (ShortArray) -> Unit,
    private val audioSource: Int = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
    private val logger: MeshLogger = AndroidMeshLogger
) {
    @Volatile private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val isRunning = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        if (!isRunning.compareAndSet(false, true)) return
        captureJob = scope.launch(Dispatchers.IO) { captureLoop() }
        logger.d("AudioCapture start requested")
    }

    suspend fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        val rec = audioRecord
        if (rec != null) runCatching { rec.stop() }
        withTimeoutOrNull(1000) { captureJob?.cancelAndJoin() }
        captureJob = null
        logger.d("AudioCapture stopped")
    }

    @SuppressLint("MissingPermission")
    private suspend fun captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        while (currentCoroutineContext().isActive && isRunning.get()) {
            val minBuf = AudioRecord.getMinBufferSize(
                AudioConfig.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val rec = AudioRecord(
                audioSource, AudioConfig.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, AudioConfig.FRAME_SAMPLES * 2 * 4)
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                logger.e("AudioRecord init failed, state=${rec.state}")
                rec.release(); delay(1000); continue
            }
            audioRecord = rec
            rec.startRecording()
            val frameBuffer = ShortArray(AudioConfig.FRAME_SAMPLES)
            var lastData = System.currentTimeMillis()
            try {
                while (currentCoroutineContext().isActive && isRunning.get()) {
                    val read = rec.read(frameBuffer, 0, frameBuffer.size)
                    val now = System.currentTimeMillis()
                    if (read > 0) { lastData = now; onPcmFrameReady(frameBuffer.copyOf(read)) }
                    if (now - lastData > 500) { logger.w("read stalled >500ms, recreating"); break }
                }
            } finally {
                audioRecord = null
                scope.launch(Dispatchers.IO) {
                    withTimeoutOrNull(500) { runCatching { rec.stop(); rec.release() } }
                        ?: logger.w("AudioRecord release timed out")
                }
            }
            delay(100)
        }
    }
}
