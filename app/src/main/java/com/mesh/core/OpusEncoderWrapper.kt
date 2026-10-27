package com.mesh.core

import java.util.concurrent.atomic.AtomicBoolean

sealed class EncodeResult {
    data class Packet(val data: ByteArray) : EncodeResult()
    object Silence : EncodeResult()
    object Error : EncodeResult()
}

/** STUB: raw PCM 16-bit LE mono 16kHz. Opus — отдельным этапом через Kopus. */
class OpusEncoderWrapper(private val logger: MeshLogger = AndroidMeshLogger) {
    private val closed = AtomicBoolean(false)

    fun encode(pcmFrame: ShortArray): EncodeResult {
        if (closed.get()) return EncodeResult.Error
        if (pcmFrame.all { it == 0.toShort() }) return EncodeResult.Silence
        val out = ByteArray(pcmFrame.size * 2)
        for (i in pcmFrame.indices) {
            out[i * 2] = (pcmFrame[i].toInt() and 0xFF).toByte()
            out[i * 2 + 1] = ((pcmFrame[i].toInt() shr 8) and 0xFF).toByte()
        }
        return EncodeResult.Packet(out)
    }

    fun release() { closed.set(true) }
}
