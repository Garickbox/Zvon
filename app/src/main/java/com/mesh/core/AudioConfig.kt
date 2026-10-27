package com.mesh.core

object AudioConfig {
    const val SAMPLE_RATE = 16_000
    const val FRAME_DURATION_MS = 20
    const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_DURATION_MS / 1000
}
