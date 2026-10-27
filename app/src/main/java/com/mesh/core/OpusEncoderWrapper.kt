package com.mesh.core

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

sealed class EncodeResult {
    data class Packet(val data: ByteArray) : EncodeResult()
    object Silence : EncodeResult()
    object Error : EncodeResult()
}

/**
 * Opus encoder через MediaCodec. Нулевые внешние зависимости.
 *
 * Если MediaCodec "audio/opus" не поддерживается на устройстве (энкодер не в списке
 * codecs), происходит graceful fallback на raw PCM (совместимо с прошлой версией).
 * Logcat/InAppLogger покажет "using raw PCM fallback".
 */
class OpusEncoderWrapper(private val logger: MeshLogger = AndroidMeshLogger) {
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
                MIME, AudioConfig.SAMPLE_RATE, 1
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, AudioConfig.BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AudioConfig.FRAME_SAMPLES * 2)
            }
            val c = MediaCodec.createEncoderByType(MIME)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            codec = c
            initialized = true
            warmup(c)
            logger.d("MediaCodec Opus encoder initialized")
            true
        } catch (e: Exception) {
            failed = true
            logger.w("MediaCodec Opus encoder unavailable — using raw PCM fallback. ${e.message}")
            false
        }
    }

    private fun warmup(c: MediaCodec) {
        try {
            val silence = ShortArray(AudioConfig.FRAME_SAMPLES)
            val inIdx = c.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                c.getInputBuffer(inIdx)!!.apply { clear(); put(shortArrayToBytes(silence)) }
                c.queueInputBuffer(inIdx, 0, silence.size * 2, 0L, 0)
            }
            val outInfo = MediaCodec.BufferInfo()
            val outIdx = c.dequeueOutputBuffer(outInfo, 10_000)
            if (outIdx >= 0) c.releaseOutputBuffer(outIdx, false)
        } catch (_: Exception) { }
    }

    fun encode(pcmFrame: ShortArray): EncodeResult {
        if (closed.get()) return EncodeResult.Error
        if (pcmFrame.all { it == 0.toShort() }) return EncodeResult.Silence
        if (!ensureInit()) return encodeRaw(pcmFrame)
        val c = codec ?: return encodeRaw(pcmFrame)
        return try {
            val inIdx = c.dequeueInputBuffer(10_000)
            if (inIdx < 0) return EncodeResult.Silence
            val pcmBytes = shortArrayToBytes(pcmFrame)
            c.getInputBuffer(inIdx)!!.apply { clear(); put(pcmBytes) }
            c.queueInputBuffer(inIdx, 0, pcmBytes.size, ptsUs, 0)
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
            if (outIdx < 0) return EncodeResult.Silence

            val out = ByteArray(outInfo.size)
            c.getOutputBuffer(outIdx)!!.apply {
                position(outInfo.offset); limit(outInfo.offset + outInfo.size); get(out)
            }
            c.releaseOutputBuffer(outIdx, false)

            if (out.isEmpty()) EncodeResult.Silence else EncodeResult.Packet(out)
        } catch (e: Exception) {
            logger.e("encode failed", e)
            EncodeResult.Error
        }
    }

    private fun encodeRaw(pcmFrame: ShortArray): EncodeResult {
        return try {
            EncodeResult.Packet(shortArrayToBytes(pcmFrame))
        } catch (e: Exception) {
            logger.e("encodeRaw failed", e); EncodeResult.Error
        }
    }

    fun release() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        initialized = false
    }

    private fun shortArrayToBytes(arr: ShortArray): ByteArray {
        val out = ByteArray(arr.size * 2)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (s in arr) bb.putShort(s)
        return out
    }

    companion object {
        const val MIME = "audio/opus"
    }
}
