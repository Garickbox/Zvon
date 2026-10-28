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
import com.mesh.core.InAppLogger
import com.mesh.core.MeshPacket
import com.mesh.core.OpusDecoderWrapper
import com.mesh.core.OpusEncoderWrapper
import com.mesh.core.PacketType
import org.json.JSONObject
import java.io.File
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.util.UUID

data class TestResult(
    val name: String,
    val ok: Boolean,
    val detail: String,
    val isWarning: Boolean = false
)

class SelfTestRunner(private val context: Context) {

    suspend fun runAll(onProgress: (TestResult) -> Unit): List<TestResult> {
        val results = mutableListOf<TestResult>()
        fun add(r: TestResult) { results.add(r); onProgress(r) }
        fun check(name: String, fn: () -> Pair<Boolean, String>): TestResult = try {
            val (ok, d) = fn(); TestResult(name, ok, d)
        } catch (e: Exception) { TestResult(name, false, "Exception: ${e.message}") }
        fun checkWarn(name: String, fn: () -> Pair<Boolean, String>): TestResult = try {
            val (ok, d) = fn(); TestResult(name, ok, d, isWarning = !ok)
        } catch (e: Exception) { TestResult(name, false, "Exception: ${e.message}", isWarning = true) }

        add(check("Разрешения", ::checkPermissions))
        add(check("FGS / MeshNode", ::checkFgs))
        add(check("WiFi P2P драйвер", ::checkWifiP2p))
        add(check("LAN IP", ::checkLanIp))
        add(check("UDP 47820", ::checkUdpPort))
        add(check("TCP 47821", ::checkTcpPort))
        add(check("LAN 47819", ::checkLanPort))
        add(check("Микрофон", ::checkAudioRecord))
        add(check("Динамик", ::checkAudioTrack))
        add(check("Кодек roundtrip", ::checkCodec))
        add(check("Протокол пакетов", ::checkPacket))
        add(check("Логгер", ::checkLogger))
        add(check("Хранилище", ::checkStorage))
        add(check("MeshNode жизнь (Stage 6)", ::checkAppLifecycle))
        add(check("Имя пира", ::checkPeerName))
        add(check("NotificationListener", ::checkNotificationListener))
        add(check("FSI (полный экран)", ::checkFullScreenIntent))
        add(check("OTA: сеть GitHub", ::checkGitHubReachable))
        add(checkWarn("OTA: новый билд", ::checkOTA))
        add(check("OTA: разрешение установки", ::checkInstallPermission))
        add(check("История чата", ::checkChatHistory))
        add(check("Контакты", ::checkContacts))
        add(check("Pipeline (если звонок)", ::checkPipelineLive))

        return results
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

    private fun checkFgs(): Pair<Boolean, String> =
        if (ZvonController.meshNode != null) true to "meshNode != null"
        else false to "meshNode == null (FGS убит?)"

    private fun checkWifiP2p(): Pair<Boolean, String> =
        if (context.getSystemService(Context.WIFI_P2P_SERVICE) != null) true to "доступен"
        else false to "Wi-Fi P2P SERVICE = null"

    private fun checkLanIp(): Pair<Boolean, String> {
        val node = ZvonController.meshNode
        val mode = node?.currentMode() ?: "?"
        val ips = mutableListOf<String>()
        val details = mutableListOf<String>()
        try {
            for (intf in java.net.NetworkInterface.getNetworkInterfaces().toList()) {
                if (!intf.isUp || intf.isLoopback) continue
                for (ia in intf.inetAddresses.toList()) {
                    if (ia is java.net.Inet4Address && !ia.isLoopbackAddress) {
                        val ip = ia.hostAddress ?: continue
                        details.add("${intf.name}=$ip")
                        if (!ip.startsWith("169.254.")) ips.add("${intf.name}:$ip")
                    }
                }
            }
        } catch (_: Exception) {}
        val summary = if (details.isEmpty()) "нет IPv4" else details.joinToString(", ")
        return when {
            ips.any { it.contains(":192.168.") || it.contains(":10.") || it.contains(":172.") } -> true to "mode=$mode; $summary"
            ips.isNotEmpty() -> true to "mode=$mode; $summary"
            mode == "P2P" -> true to "mode=P2P; $summary"
            else -> false to "mode=$mode; $summary"
        }
    }

    private fun checkUdpPort(): Pair<Boolean, String> {
        if (ZvonController.meshNode?.isInGroup() == true) return true to "обслуживается meshNode"
        return try {
            val s = DatagramSocket(null); s.reuseAddress = true
            s.bind(InetSocketAddress(47820)); s.close()
            true to "свободен"
        } catch (e: Exception) { false to "занят: ${e.message}" }
    }

    private fun checkTcpPort(): Pair<Boolean, String> {
        if (ZvonController.meshNode?.isInGroup() == true) {
            return try {
                java.net.Socket().use { sock ->
                    sock.connect(InetSocketAddress("127.0.0.1", 47821), 500)
                    true to "сервер файлов слушает"
                }
            } catch (e: Exception) { false to "не отвечает: ${e.message}" }
        }
        return try {
            val s = ServerSocket(); s.reuseAddress = true
            s.bind(InetSocketAddress(47821)); s.close()
            true to "свободен"
        } catch (e: Exception) { false to "занят: ${e.message}" }
    }

    private fun checkLanPort(): Pair<Boolean, String> {
        if (ZvonController.meshNode?.currentMode() == "LAN") return true to "LAN активен"
        return try {
            val s = DatagramSocket(null); s.reuseAddress = true
            s.bind(InetSocketAddress(47819)); s.close()
            true to "свободен"
        } catch (e: Exception) { false to "занят: ${e.message}" }
    }

    private fun checkAudioRecord(): Pair<Boolean, String> {
        val min = AudioRecord.getMinBufferSize(
            AudioConfig.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false to "getMinBufferSize = $min"
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            return false to "нет RECORD_AUDIO"
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                AudioConfig.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096))
        } catch (e: Exception) { return false to "ctor: ${e.message}" }
        val ok = rec.state == AudioRecord.STATE_INITIALIZED
        rec.release()
        return if (ok) true to "minBuf=$min" else false to "state != INITIALIZED"
    }

    private fun checkAudioTrack(): Pair<Boolean, String> {
        val min = AudioTrack.getMinBufferSize(
            AudioConfig.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
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
        return if (ok) true to "minBuf=$min" else false to "state != INITIALIZED"
    }

    private fun checkCodec(): Pair<Boolean, String> {
        val enc = OpusEncoderWrapper(); val dec = OpusDecoderWrapper()
        return try {
            val pcm = ShortArray(AudioConfig.FRAME_SAMPLES) { i ->
                (8000 * kotlin.math.sin(2 * Math.PI * 440 * i / AudioConfig.SAMPLE_RATE)).toInt().toShort()
            }
            val r = enc.encode(pcm)
            if (r !is com.mesh.core.EncodeResult.Packet) {
                enc.release(); dec.release()
                return false to "encode=${r::class.simpleName}"
            }
            val decoded = dec.decode(r.data)
            enc.release(); dec.release()
            if (decoded != null && decoded.size == pcm.size) true to "20мс 440Гц OK (${r.data.size}B)"
            else false to "size ${decoded?.size} != ${pcm.size}"
        } catch (e: Exception) { enc.release(); dec.release(); false to "${e.message}" }
    }

    private fun checkPacket(): Pair<Boolean, String> {
        return try {
            val p = MeshPacket(PacketType.TEXT, UUID.randomUUID(), MeshPacket.BROADCAST,
                System.nanoTime(), "selftest".toByteArray())
            val enc = MeshPacket.encode(p)
            val dec = MeshPacket.decode(enc)
            if (dec == null) false to "decode = null"
            else if (dec.type == p.type && dec.sourceId == p.sourceId) true to "${enc.size}B"
            else false to "mismatch"
        } catch (e: Exception) { false to "${e.message}" }
    }

    private fun checkLogger(): Pair<Boolean, String> = try {
        val before = InAppLogger.sizeBytes()
        InAppLogger.write('I', "SelfTest", "line ${System.currentTimeMillis()}")
        InAppLogger.flush()
        val after = InAppLogger.sizeBytes()
        if (after >= before) true to "${after / 1024}КБ" else false to "не растёт"
    } catch (e: Exception) { false to "${e.message}" }

    private fun checkStorage(): Pair<Boolean, String> = try {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Zvon")
        dir.mkdirs()
        val f = File(dir, ".selftest_${System.currentTimeMillis()}")
        f.writeText("ok"); val r = f.readText(); f.delete()
        if (r == "ok") true to "${dir.absolutePath}" else false to "readback failed"
    } catch (e: Exception) { false to "${e.message}" }

    private fun checkAppLifecycle(): Pair<Boolean, String> {
        val m = ZvonController.meshNode ?: return false to "meshNode null"
        val pip = ZvonController.pipeline
        return true to "mode=${m.currentMode()} inGroup=${m.isInGroup()} pipeline=${if (pip != null) "alive" else "null"}"
    }

    private fun checkPeerName(): Pair<Boolean, String> {
        val m = ZvonController.meshNode ?: return false to "meshNode null"
        val myName = m.myDisplayName
        val peerId = m.currentPeerId()
        val peerName = peerId?.let { m.contactsStore.get(it)?.name }
        return if (peerName != null && peerName.isNotBlank() && peerName != peerId) {
            true to "я=$myName, пир=$peerName"
        } else true to "я=$myName, пир=${peerName ?: "ждём HELLO"}"
    }

    private fun checkNotificationListener(): Pair<Boolean, String> =
        if (ZvonNotificationListener.connected) true to "подключён (${ZvonNotificationListener.receivedCount})"
        else false to "не подключён — Настройки → Уведомления → Доступ"

    private fun checkFullScreenIntent(): Pair<Boolean, String> {
        if (Build.VERSION.SDK_INT < 34) return true to "Android ${Build.VERSION.SDK_INT} < 14 — не требуется"
        return try {
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
            if (nm.canUseFullScreenIntent()) true to "✅ разрешено"
            else false to "❌ Настройки → Приложения → Zvon → Полноэкранные"
        } catch (e: Exception) { false to "${e.message}" }
    }

    private fun checkGitHubReachable(): Pair<Boolean, String> = try {
        val conn = URL("https://api.github.com/repos/Garickbox/Zvon/releases/latest").openConnection() as HttpURLConnection
        conn.requestMethod = "HEAD"; conn.setRequestProperty("User-Agent", "Zvon")
        conn.connectTimeout = 5000; conn.readTimeout = 5000
        val code = conn.responseCode; conn.disconnect()
        if (code in 200..399) true to "HTTP $code" else false to "HTTP $code"
    } catch (e: Exception) { false to "нет связи: ${e.message?.take(50)}" }

    private fun checkOTA(): Pair<Boolean, String> {
        return try {
            val conn = URL("https://api.github.com/repos/Garickbox/Zvon/releases/latest").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"; conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "Zvon")
            conn.connectTimeout = 5000; conn.readTimeout = 5000
            if (conn.responseCode != 200) {
                true to "HTTP ${conn.responseCode}"
            } else {
                val json = conn.inputStream.bufferedReader().use { it.readText() }
                val tag = JSONObject(json).optString("tag_name", "")
                val latest = tag.removePrefix("build-").toIntOrNull()
                conn.disconnect()
                val current = try { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt() } catch (_: Exception) { 0 }
                when {
                    latest == null -> true to "тег: $tag"
                    latest > current -> false to "доступно build-$latest (у тебя $current)"
                    else -> true to "последняя (build-$current)"
                }
            }
        } catch (e: Exception) { true to "отложено: ${e.message?.take(40)}" }
    }

    private fun checkInstallPermission(): Pair<Boolean, String> =
        if (Build.VERSION.SDK_INT >= 26) {
            if (context.packageManager.canRequestPackageInstalls()) true to "разрешена"
            else false to "нет REQUEST_INSTALL_PACKAGES"
        } else true to "не требуется"

    private fun checkChatHistory(): Pair<Boolean, String> {
        val m = ZvonController.meshNode ?: return false to "meshNode null"
        return try {
            val peers = m.chatStore.allPeers()
            val total = peers.sumOf { m.chatStore.history(it).size }
            if (peers.isEmpty()) true to "0 диалогов" else true to "${peers.size} диалогов, $total сообщений"
        } catch (e: Exception) { false to "${e.message}" }
    }

    private fun checkContacts(): Pair<Boolean, String> {
        val m = ZvonController.meshNode ?: return false to "meshNode null"
        return try {
            val all = m.contactsStore.all()
            val online = all.count { it.online }
            val withName = all.count { it.name.isNotBlank() && it.name != it.nodeId }
            true to "${all.size} контактов, $online онлайн, $withName с именами"
        } catch (e: Exception) { false to "${e.message}" }
    }

    private fun checkPipelineLive(): Pair<Boolean, String> {
        val p = ZvonController.pipeline
        val m = ZvonController.meshNode
        val state = m?.currentMode() ?: "?"
        return if (p == null) true to "нет активного звонка — ок"
        else true to "alive, mode=$state, ${runCatching { p.statsString() }.getOrNull()?.take(70) ?: "?"}"
    }
}
