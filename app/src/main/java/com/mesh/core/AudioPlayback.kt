package com.mesh.core

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class AudioPlayback(
    private val jitterBuffer: JitterBuffer,
    private val decoder: OpusDecoderWrapper,
    private val logger: MeshLogger = AndroidMeshLogger
) {
    companion object {
        private const val JOIN_TIMEOUT_MS = 3_000L
        private const val BATCH_FRAMES = 2
        private const val TRACK_BUFFER_FRAMES = 4
    }

    private val running = AtomicBoolean(false)
    @Volatile private var playbackThread: Thread? = null
    @Volatile private var leakedThread: Thread? = null
    @Volatile private var audioTrack: AudioTrack? = null
    private val directQueue = LinkedBlockingQueue<ShortArray>(3)
    @Volatile var isRawMode = false

    @Volatile var framesWritten = 0L; private set
    @Volatile var plcFramesWritten = 0L; private set
    @Volatile var underrunCount = 0L; private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        playbackThread = Thread({ playbackLoop() }, "MeshAudioPlayback").apply { start() }
        logger.d("AudioPlayback start (batch=$BATCH_FRAMES, trackBuf=$TRACK_BUFFER_FRAMES)")
    }

    fun writeDirect(pcm: ShortArray) {
        if (isRawMode && !directQueue.offer(pcm)) logger.w("writeDirect: queue full")
    }

    fun isThreadAlive(): Boolean =
        leakedThread?.isAlive == true || playbackThread?.isAlive == true

    suspend fun stop() {
        if (!running.compareAndSet(true, false)) return
        val t = audioTrack
        runCatching { t?.pause() }; runCatching { t?.stop() }
        val thread = playbackThread
        if (thread != null) {
            withContext(Dispatchers.IO) { thread.join(JOIN_TIMEOUT_MS) }
            if (thread.isAlive) leakedThread = thread
        }
        playbackThread = null
        logger.d("AudioPlayback stopped")
    }

    private fun playbackLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val track = createTrack()
        if (track == null) { logger.e("AudioTrack init failed"); running.set(false); return }
        audioTrack = track
        try {
            track.play()
            val batchSize = AudioConfig.FRAME_SAMPLES * BATCH_FRAMES
            val batchBuffer = ShortArray(batchSize)

            while (running.get()) {
                for (i in 0 until BATCH_FRAMES) {
                    val pcm: ShortArray? = if (isRawMode) {
                        directQueue.poll()
                    } else {
                        val opus = jitterBuffer.getNextFrame()
                        if (opus != null) decoder.decode(opus) else null
                    }
                    val frame = pcm ?: ShortArray(AudioConfig.FRAME_SAMPLES)
                    if (pcm == null) plcFramesWritten++
                    System.arraycopy(frame, 0, batchBuffer, i * AudioConfig.FRAME_SAMPLES, AudioConfig.FRAME_SAMPLES)
                }
                try {
                    val written = track.write(batchBuffer, 0, batchSize, AudioTrack.WRITE_BLOCKING)
                    if (written > 0) framesWritten += BATCH_FRAMES
                    if (written < batchSize && written >= 0) underrunCount++
                } catch (e: Exception) { logger.e("write failed", e); return }
            }
        } finally {
            audioTrack = null
            runCatching { track.stop(); track.release() }
        }
    }

    private fun createTrack(): AudioTrack? {
        val minBuf = AudioTrack.getMinBufferSize(
            AudioConfig.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        // USAGE_VOICE_COMMUNICATION + CONTENT_TYPE_SPEECH = звонковый маршрут.
        // В паре с AudioManager.MODE_IN_COMMUNICATION + setSpeakerphoneOn(true) → громкая связь.
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(AudioConfig.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        val t = try {
            AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(minBuf, AudioConfig.FRAME_SAMPLES * 2 * TRACK_BUFFER_FRAMES))
                .setTransferMode(AudioTrack.MODE_STREAM).build()
        } catch (e: Exception) { logger.e("AudioTrack.Builder failed", e); return null }
        if (t.state != AudioTrack.STATE_INITIALIZED) { t.release(); return null }
        return t
    }
}
