package com.mesh.core

import java.nio.ByteBuffer

data class VoicePayload(val seq: Long, val opusData: ByteArray) {
    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(8 + opusData.size)
        buf.putLong(seq); buf.put(opusData); return buf.array()
    }

    companion object {
        fun decode(d: ByteArray): VoicePayload? {
            if (d.size < 8) return null
            val buf = ByteBuffer.wrap(d)
            val seq = buf.long
            val opus = ByteArray(buf.remaining()).also { buf.get(it) }
            return VoicePayload(seq, opus)
        }
    }
}
