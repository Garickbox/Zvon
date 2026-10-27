package com.mesh.core

object AudioConfig {
    const val SAMPLE_RATE = 16_000
    const val FRAME_DURATION_MS = 20
    const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_DURATION_MS / 1000
    const val BITRATE = 24_000
    const val MAX_PACKET_LOSS_PERC = 10
}
