package com.mesh.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

data class MeshPacket(
    val type: PacketType,
    val sourceId: UUID,
    val destId: UUID,
    val seq: Long,
    val payload: ByteArray
) {
    companion object {
        const val MAGIC: Short = 0x4D53
        const val HEADER = 42  // 2+1+16+16+8 = 43? fix: 2+1+16+16+8 = 43; use 43
        val BROADCAST: UUID = UUID.fromString("00000000-0000-0000-0000-FFFFFFFFFFFF")

        fun encode(p: MeshPacket): ByteArray {
            val buf = ByteBuffer.allocate(43 + p.payload.size).order(ByteOrder.BIG_ENDIAN)
            buf.putShort(MAGIC)
            buf.put(p.type.id)
            buf.putLong(p.sourceId.mostSignificantBits); buf.putLong(p.sourceId.leastSignificantBits)
            buf.putLong(p.destId.mostSignificantBits); buf.putLong(p.destId.leastSignificantBits)
            buf.putLong(p.seq)
            buf.put(p.payload)
            return buf.array()
        }

        fun decode(d: ByteArray): MeshPacket? {
            if (d.size < 43) return null
            val buf = ByteBuffer.wrap(d).order(ByteOrder.BIG_ENDIAN)
            if (buf.short != MAGIC) return null
            val type = PacketType.fromId(buf.get()) ?: return null
            val src = UUID(buf.long, buf.long)
            val dst = UUID(buf.long, buf.long)
            val seq = buf.long
            val payload = ByteArray(buf.remaining()).also { buf.get(it) }
            return MeshPacket(type, src, dst, seq, payload)
        }
    }
}
