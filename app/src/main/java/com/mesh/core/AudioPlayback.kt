package com.mesh.core

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

class AudioPlayback(
    private val jitterBuffer: JitterBuffer,
    private val decoder: OpusDecoderWrapper,
    private val logger: MeshLogger = AndroidMeshLogger
) {
    companion object {
        private const val FRAME_NS = AudioConfig.FRAME_DURATION_MS * 1_000_000L
        private const val DRIFT_RESYNC_NS = 100_000_000L
        private const val JOIN_TIMEOUT_MS = 3_000L
        private const val DRIFT_WARN_AFTER_FRAMES = 250L
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
    private var driftWarnCount = 0

    fun start() {
        if (!running.compareAndSet(false, true)) return
        playbackThread = Thread({ playbackLoop() }, "MeshAudioPlayback").apply { start() }
        logger.d("AudioPlayback start requested")
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
            if (thread.isAlive) {
                logger.e("playback thread did not exit in ${JOIN_TIMEOUT_MS}ms — marking leaked")
                leakedThread = thread
            }
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
            var nextDeadlineNs = System.nanoTime() + FRAME_NS
            while (running.get()) {
                val pcm: ShortArray? = if (isRawMode) {
                    directQueue.poll() ?: ShortArray(AudioConfig.FRAME_SAMPLES)
                } else {
                    val opus = jitterBuffer.getNextFrame()
                    if (opus != null) decoder.decode(opus)
                    else { plcFramesWritten++; decoder.decodePlc() }
                }
                val toWrite = pcm ?: ShortArray(AudioConfig.FRAME_SAMPLES)
                try {
                    val written = track.write(toWrite, 0, toWrite.size, AudioTrack.WRITE_BLOCKING)
                    if (written > 0) framesWritten++
                    if (written < toWrite.size && written >= 0) underrunCount++
                } catch (e: Exception) { logger.e("write failed", e); return }
                val now = System.nanoTime()
                val sleepNs = nextDeadlineNs - now
                if (sleepNs > 0) LockSupport.parkNanos(sleepNs)
                else if (-sleepNs > DRIFT_RESYNC_NS) {
                    if (framesWritten > DRIFT_WARN_AFTER_FRAMES && driftWarnCount < 3) {
                        logger.w("drift ${-sleepNs / 1_000_000}ms — resync (frame #$framesWritten)")
                        driftWarnCount++
                    }
                    nextDeadlineNs = now + FRAME_NS; continue
                }
                nextDeadlineNs += FRAME_NS
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
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(AudioConfig.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        val t = try {
            AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(minBuf, AudioConfig.FRAME_SAMPLES * 2 * 3))
                .setTransferMode(AudioTrack.MODE_STREAM).build()
        } catch (e: Exception) { logger.e("AudioTrack.Builder failed", e); return null }
        if (t.state != AudioTrack.STATE_INITIALIZED) { t.release(); return null }
        return t
    }
}
