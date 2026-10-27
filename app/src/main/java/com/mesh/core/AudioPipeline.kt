package com.mesh.core
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong

enum class PipelineMode { RAW_CODEC, FULL_LOOPBACK }

class AudioPipeline(
    private val mode: PipelineMode,
    private val logger: MeshLogger = AndroidMeshLogger
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val encoder = OpusEncoderWrapper(logger)
    private val decoder = OpusDecoderWrapper(logger)
    private val jitterBuffer = JitterBuffer(60L, AudioConfig.FRAME_DURATION_MS.toLong())
    private lateinit var capture: AudioCapture
    private lateinit var playback: AudioPlayback
    private val loopbackSeq = AtomicLong(0)

    fun start() {
        playback = AudioPlayback(jitterBuffer, decoder, logger)
        if (mode == PipelineMode.RAW_CODEC) playback.isRawMode = true
        playback.start()
        capture = AudioCapture(
            onPcmFrameReady = ::onPcmFrame,
            audioSource = android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            logger = logger
        )
        capture.start()
        logger.d("AudioPipeline started (mode=$mode)")
    }

    suspend fun stop() {
        capture.stop()
        playback.stop()
        logger.d(
            "Pipeline stopped: mode=$mode frames=${playback.framesWritten} " +
            "playbackPlc=${playback.plcFramesWritten} underruns=${playback.underrunCount} " +
            "jitter=[${jitterBuffer.stats()}]"
        )
        if (playback.isThreadAlive()) {
            logger.e("Skipping codec release to avoid UAF")
            scope.cancel(); return
        }
        encoder.release(); decoder.release()
        scope.cancel()
    }

    private fun onPcmFrame(pcm: ShortArray) {
        when (mode) {
            PipelineMode.RAW_CODEC -> when (val r = encoder.encode(pcm)) {
                is EncodeResult.Packet -> {
                    val decoded = decoder.decode(r.data)
                    if (decoded != null) playback.writeDirect(decoded)
                }
                else -> {}
            }
            PipelineMode.FULL_LOOPBACK -> when (val r = encoder.encode(pcm)) {
                is EncodeResult.Packet -> jitterBuffer.receive(loopbackSeq.incrementAndGet(), r.data)
                EncodeResult.Silence -> loopbackSeq.incrementAndGet()
                EncodeResult.Error -> logger.e("encode error")
            }
        }
    }
}
