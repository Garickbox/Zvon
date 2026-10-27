package com.mesh.core

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class OpusDecoderWrapper(private val logger: MeshLogger = AndroidMeshLogger) {
    private val closed = AtomicBoolean(false)
    @Volatile private var codec: MediaCodec? = null
    @Volatile private var initialized = false
    @Volatile private var failed = false
    private var ptsUs = 0L

    @Synchronized
    private fun ensureInit(): Boolean {
        if (initialized) return true
        if (failed) return false
        if (closed.get()) return false
        return try {
            val format = MediaFormat.createAudioFormat(
                OpusEncoderWrapper.MIME, AudioConfig.SAMPLE_RATE, 1
            )
            val c = MediaCodec.createDecoderByType(OpusEncoderWrapper.MIME)
            c.configure(format, null, null, 0)
            c.start()
            codec = c
            initialized = true
            logger.d("MediaCodec Opus decoder initialized")
            true
        } catch (e: Exception) {
            failed = true
            logger.w("MediaCodec Opus decoder unavailable — raw PCM fallback. ${e.message}")
            false
        }
    }

    fun decode(opusPacket: ByteArray): ShortArray? {
        if (closed.get()) return null
        if (opusPacket.isEmpty()) return null
        if (!ensureInit()) return decodeRaw(opusPacket)
        val c = codec ?: return decodeRaw(opusPacket)
        return try {
            val inIdx = c.dequeueInputBuffer(10_000)
            if (inIdx < 0) return null
            c.getInputBuffer(inIdx)!!.apply { clear(); put(opusPacket) }
            c.queueInputBuffer(inIdx, 0, opusPacket.size, ptsUs, 0)
            ptsUs += AudioConfig.FRAME_DURATION_MS * 1_000L

            val outInfo = MediaCodec.BufferInfo()
            var outIdx = -1
            val startNs = System.nanoTime()
            var remaining = 10_000L
            while (remaining > 0) {
                outIdx = c.dequeueOutputBuffer(outInfo, remaining)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    remaining = 10_000L - (System.nanoTime() - startNs) / 1000
                    continue
                }
                if (outIdx >= 0) break
            }
            if (outIdx < 0) return null

            val pcm = ByteArray(outInfo.size)
            c.getOutputBuffer(outIdx)!!.apply {
                position(outInfo.offset); limit(outInfo.offset + outInfo.size); get(pcm)
            }
            c.releaseOutputBuffer(outIdx, false)
            bytesToShortArray(pcm)
        } catch (e: Exception) {
            logger.e("decode failed", e); null
        }
    }

    private fun decodeRaw(packet: ByteArray): ShortArray? = try {
        bytesToShortArray(packet)
    } catch (_: Exception) { null }

    fun decodePlc(): ShortArray? = ShortArray(AudioConfig.FRAME_SAMPLES)

    fun release() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null; initialized = false
    }

    private fun bytesToShortArray(bytes: ByteArray): ShortArray {
        val out = ShortArray(bytes.size / 2)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in out.indices) out[i] = bb.short
        return out
    }
}
