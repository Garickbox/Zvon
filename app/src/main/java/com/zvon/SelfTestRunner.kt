package com.zvon

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import com.mesh.core.AudioConfig
import com.mesh.core.MeshPacket
import com.mesh.core.OpusDecoderWrapper
import com.mesh.core.OpusEncoderWrapper
import com.mesh.core.PacketType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.UUID

data class TestResult(
    val name: String,
    val ok: Boolean,
    val detail: String
)

class SelfTestRunner(private val context: Context) {

    suspend fun runAll(onProgress: (TestResult) -> Unit): List<TestResult> {
        val results = mutableListOf<TestResult>()
        fun add(r: TestResult) { results.add(r); onProgress(r) }

        add(check("Разрешения", ::checkPermissions))
        add(check("FGS-сервис", ::checkFgs))
        add(check("Wi-Fi P2P драйвер", ::checkWifiP2p))
        add(check("UDP bind 47820", ::checkUdpPort))
        add(check("TCP bind 47821", ::checkTcpPort))
        add(check("Микрофон (AudioRecord)", ::checkAudioRecord))
        add(check("Динамик (AudioTrack)", ::checkAudioTrack))
        add(check("Кодек roundtrip", ::checkCodec))
        add(check("Протокол пакетов", ::checkPacket))
        add(check("Логгер", ::checkLogger))
        add(check("Хранилище", ::checkStorage))
        add(check("MeshNode", ::checkMeshNode))
        add(check("NotificationListener", ::checkNotificationListener))
        return results
    }

    private suspend fun check(name: String, fn: () -> Pair<Boolean, String>): TestResult =
        withContext(Dispatchers.IO) {
            try { val (ok, d) = fn(); TestResult(name, ok, d) }
            catch (e: Exception) { TestResult(name, false, "Exception: ${e.message}") }
        }

    private fun checkPermissions(): Pair<Boolean, String> {
        val needed = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) {
            needed += Manifest.permission.NEARBY_WIFI_DEVICES
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        return if (missing.isEmpty()) true to "все выданы"
        else false to "не выданы: ${missing.joinToString { it.substringAfterLast('.') }}"
    }

    private fun checkFgs(): Pair<Boolean, String> {
        return if (ZvonController.meshNode != null) true to "активен, meshNode != null"
        else false to "meshNode == null (FGS не запущен?)"
    }

    private fun checkWifiP2p(): Pair<Boolean, String> {
        val mgr = context.getSystemService(Context.WIFI_P2P_SERVICE)
        return if (mgr != null) true to "Wi-Fi Direct доступен"
        else false to "Wi-Fi P2P SERVICE = null"
    }

    private fun checkUdpPort(): Pair<Boolean, String> {
        // Если мы уже в группе — UDP-сокет держит meshNode, это правильное поведение
        if (ZvonController.meshNode?.isInGroup() == true) {
            return true to "обслуживается meshNode (в группе)"
        }
        return try {
            val s = DatagramSocket(null)
            s.reuseAddress = true
            s.bind(InetSocketAddress(47820))
            s.close()
            true to "порт 47820 свободен"
        } catch (e: Exception) { false to "занят/ошибка: ${e.message}" }
    }

    private fun checkTcpPort(): Pair<Boolean, String> {
        // Если в группе — порт держит FileTransferManager, пробуем подключиться (значит сервер жив)
        if (ZvonController.meshNode?.isInGroup() == true) {
            return try {
                java.net.Socket().use { sock ->
                    sock.connect(InetSocketAddress("127.0.0.1", 47821), 500)
                    true to "сервер файлов слушает (в группе)"
                }
            } catch (e: Exception) {
                false to "сервер файлов не отвечает: ${e.message}"
            }
        }
        // Не в группе — проверяем bind
        return try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress(47821))
            s.close()
            true to "порт 47821 свободен"
        } catch (e: Exception) { false to "занят/ошибка: ${e.message}" }
    }

    private fun checkAudioRecord(): Pair<Boolean, String> {
        val min = AudioRecord.getMinBufferSize(
            AudioConfig.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (min <= 0) return false to "getMinBufferSize = $min"
        val perm = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        if (perm != PackageManager.PERMISSION_GRANTED) return false to "нет RECORD_AUDIO"
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                AudioConfig.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096)
            )
        } catch (e: Exception) { return false to "ctor: ${e.message}" }
        val ok = rec.state == AudioRecord.STATE_INITIALIZED
        rec.release()
        return if (ok) true to "init OK, minBuf=$min" else false to "state != INITIALIZED"
    }

    private fun checkAudioTrack(): Pair<Boolean, String> {
        val min = AudioTrack.getMinBufferSize(
            AudioConfig.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (min <= 0) return false to "getMinBufferSize = $min"
        val t = try {
            AudioTrack.Builder()
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AudioConfig.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(min, 4096))
                .setTransferMode(AudioTrack.MODE_STREAM).build()
        } catch (e: Exception) { return false to "ctor: ${e.message}" }
        val ok = t.state == AudioTrack.STATE_INITIALIZED
        t.release()
        return if (ok) true to "init OK, minBuf=$min" else false to "state != INITIALIZED"
    }

    private fun checkCodec(): Pair<Boolean, String> {
        val enc = OpusEncoderWrapper()
        val dec = OpusDecoderWrapper()
        try {
            // Синус 440Гц, 20мс
            val pcm = ShortArray(AudioConfig.FRAME_SAMPLES) { i ->
                (8000 * kotlin.math.sin(2 * Math.PI * 440 * i / AudioConfig.SAMPLE_RATE)).toInt().toShort()
            }
            val encResult = enc.encode(pcm)
            if (encResult !is com.mesh.core.EncodeResult.Packet) {
                enc.release(); dec.release()
                return false to "encode → ${encResult::class.simpleName}"
            }
            val decoded = dec.decode(encResult.data)
            enc.release(); dec.release()
            return if (decoded != null && decoded.size == pcm.size) true to "20мс 440Гц OK (${encResult.data.size}B)"
            else false to "decoded size ${decoded?.size} != ${pcm.size}"
        } catch (e: Exception) {
            enc.release(); dec.release()
            return false to "Exception: ${e.message}"
        }
    }

    private fun checkPacket(): Pair<Boolean, String> {
        return try {
            val p = MeshPacket(
                PacketType.TEXT,
                UUID.randomUUID(), MeshPacket.BROADCAST,
                System.nanoTime(),
                "selftest".toByteArray()
            )
            val encoded = MeshPacket.encode(p)
            val decoded = MeshPacket.decode(encoded) ?: return false to "decode = null"
            val ok = decoded.type == p.type && decoded.sourceId == p.sourceId
            if (ok) true to "encode/decode OK (${encoded.size}B)"
            else false to "mismatch type/src"
        } catch (e: Exception) { false to "Exception: ${e.message}" }
    }

    private fun checkLogger(): Pair<Boolean, String> {
        return try {
            val before = com.mesh.core.InAppLogger.sizeBytes()
            com.mesh.core.InAppLogger.write('I', "SelfTest", "test line ${System.currentTimeMillis()}")
            com.mesh.core.InAppLogger.flush()
            val after = com.mesh.core.InAppLogger.sizeBytes()
            if (after >= before) true to "размер ${after / 1024}КБ"
            else false to "размер не растёт"
        } catch (e: Exception) { false to "Exception: ${e.message}" }
    }

    private fun checkStorage(): Pair<Boolean, String> {
        return try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Zvon")
            dir.mkdirs()
            val f = File(dir, ".selftest_${System.currentTimeMillis()}")
            f.writeText("ok"); val read = f.readText(); f.delete()
            if (read == "ok") true to "${dir.absolutePath} доступен"
            else false to "readback failed"
        } catch (e: Exception) { false to "Exception: ${e.message}" }
    }

    private fun checkMeshNode(): Pair<Boolean, String> {
        val m = ZvonController.meshNode ?: return false to "null"
        val id = m.myId.toString().take(8)
        val inGroup = m.isInGroup()
        return true to "id=$id, inGroup=$inGroup"
    }

    private fun checkNotificationListener(): Pair<Boolean, String> {
        return if (ZvonNotificationListener.connected) true to "подключён (получено ${ZvonNotificationListener.receivedCount})"
        else false to "не подключён — Настройки → Уведомления → Доступ к уведомлениям"
    }
}
