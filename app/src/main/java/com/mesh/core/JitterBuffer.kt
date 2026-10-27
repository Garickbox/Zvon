package com.mesh.core
import java.util.TreeMap

class JitterBuffer(
    private val targetDelayMs: Long = 80L,
    private val frameDurationMs: Long = 20L,
    private val maxCapacity: Int = 50,
    private val maxConsecutivePlc: Int = 12,
    private val startTimeoutMs: Long = 600L,
    private val starvationResetMs: Long = 500L
) {
    private val buffer = TreeMap<Long, ByteArray>()
    private var lastPlayedSeq: Long = -1
    private var started = false
    private var firstPacketAtMs: Long = 0
    private var lastActivityMs: Long = 0
    private var consecutivePlc = 0

    var packetsReceived = 0L; private set
    var packetsPlayed = 0L; private set
    var packetsLost = 0L; private set
    var packetsLate = 0L; private set
    var packetsOverflow = 0L; private set
    var plcFramesGenerated = 0L; private set
    var resetsDueToStarvation = 0L; private set

    @Synchronized
    fun receive(seq: Long, payload: ByteArray, nowMs: Long = System.currentTimeMillis()) {
        packetsReceived++
        lastActivityMs = nowMs
        if (firstPacketAtMs == 0L) firstPacketAtMs = nowMs
        if (started && seq <= lastPlayedSeq) { packetsLate++; return }
        buffer[seq] = payload
        while (buffer.size > maxCapacity) { buffer.pollFirstEntry(); packetsOverflow++ }
    }

    @Synchronized
    fun getNextFrame(nowMs: Long = System.currentTimeMillis()): ByteArray? {
        if (firstPacketAtMs == 0L) return null
        if (!started) {
            if (buffer.isEmpty()) return null
            val bufferedMs = buffer.size * frameDurationMs
            val waitedMs = nowMs - firstPacketAtMs
            if (bufferedMs < targetDelayMs && waitedMs < startTimeoutMs) return null
            started = true
            lastPlayedSeq = buffer.firstKey() - 1
        }
        val expected = lastPlayedSeq + 1
        val packet = buffer.remove(expected)
        lastPlayedSeq = expected
        if (packet != null) { consecutivePlc = 0; packetsPlayed++; return packet }
        packetsLost++; plcFramesGenerated++; consecutivePlc++
        if (consecutivePlc > maxConsecutivePlc && buffer.isEmpty() && nowMs - lastActivityMs > starvationResetMs) {
            reset(); resetsDueToStarvation++
        }
        return null
    }

    @Synchronized
    fun reset() {
        buffer.clear(); lastPlayedSeq = -1; started = false
        firstPacketAtMs = 0; lastActivityMs = 0; consecutivePlc = 0
    }

    @Synchronized fun depth(): Int = buffer.size
    @Synchronized fun stats(): String =
        "rx=$packetsReceived late=$packetsLate lost=$packetsLost plc=$plcFramesGenerated reset=$resetsDueToStarvation depth=${buffer.size}"
}
