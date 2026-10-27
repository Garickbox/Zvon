package com.mesh.core

import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong

enum class PipelineMode { RAW_CODEC, FULL_LOOPBACK, NETWORK }

class AudioPipeline(
    private val mode: PipelineMode,
    private val logger: MeshLogger = AndroidMeshLogger,
    voiceSender: (suspend (seq: Long, data: ByteArray, ip: String) -> Unit)? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val encoder = OpusEncoderWrapper(logger)
    private val decoder = OpusDecoderWrapper(logger)
    private val jitterBuffer = JitterBuffer(60L, AudioConfig.FRAME_DURATION_MS.toLong())
    private lateinit var capture: AudioCapture
    private lateinit var playback: AudioPlayback
    private val loopbackSeq = AtomicLong(0)

    @Volatile var muted: Boolean = false

    private val voiceTransport: VoiceTransport? = if (mode == PipelineMode.NETWORK) {
        requireNotNull(voiceSender) { "voiceSender required for NETWORK" }
        VoiceTransport(voiceSender, logger)
    } else null

    fun start(peerIp: String? = null) {
        logger.d("AudioPipeline.start mode=$mode peer=$peerIp")
        playback = AudioPlayback(jitterBuffer, decoder, logger)
        if (mode == PipelineMode.RAW_CODEC) playback.isRawMode = true
        playback.start()

        capture = AudioCapture(
            onPcmFrameReady = ::onPcmFrame,
            audioSource = android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            logger = logger
        )
        capture.start()

        if (mode == PipelineMode.NETWORK && peerIp != null) {
            voiceTransport?.startCall(peerIp)
        }
    }

    fun onNetworkVoicePacket(seq: Long, opusData: ByteArray) {
        jitterBuffer.receive(seq, opusData)
    }

    suspend fun stop() {
        logger.d("AudioPipeline.stop mode=$mode")
        capture.stop()
        playback.stop()
        logger.d(
            "Pipeline stopped: mode=$mode frames=${playback.framesWritten} " +
            "plc=${playback.plcFramesWritten} underruns=${playback.underrunCount} " +
            "jitter=[${jitterBuffer.stats()}]"
        )
        if (playback.isThreadAlive()) {
            logger.e("Skipping codec release — playback thread leaked")
            voiceTransport?.close(); scope.cancel(); return
        }
        encoder.release(); decoder.release()
        voiceTransport?.close()
        scope.cancel()
    }

    private fun onPcmFrame(pcm: ShortArray) {
        if (muted) {
            if (mode == PipelineMode.NETWORK) voiceTransport?.onSilence()
            return
        }
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
            PipelineMode.NETWORK -> when (val r = encoder.encode(pcm)) {
                is EncodeResult.Packet -> voiceTransport?.onEncoded(r.data)
                EncodeResult.Silence -> voiceTransport?.onSilence()
                EncodeResult.Error -> logger.e("encode error")
            }
        }
    }
}
