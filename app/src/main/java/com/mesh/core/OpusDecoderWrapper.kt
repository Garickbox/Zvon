package com.mesh.core

import java.util.concurrent.atomic.AtomicBoolean

/** STUB: raw PCM. */
class OpusDecoderWrapper(private val logger: MeshLogger = AndroidMeshLogger) {
    private val closed = AtomicBoolean(false)

    fun decode(packet: ByteArray): ShortArray? {
        if (closed.get()) return null
        val samples = ShortArray(packet.size / 2)
        for (i in samples.indices) {
            val lo = packet[i * 2].toInt() and 0xFF
            val hi = packet[i * 2 + 1].toInt() and 0xFF
            samples[i] = ((hi shl 8) or lo).toShort()
        }
        return samples
    }

    fun decodePlc(): ShortArray? = ShortArray(AudioConfig.FRAME_SAMPLES)

    fun release() { closed.set(true) }
}
