package com.mesh.core

import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class PeerTestRunner(
    private val mesh: MeshNode
) {
    data class Report(
        val success: Boolean,
        val rttMin: Long, val rttAvg: Long, val rttMax: Long,
        val voiceSent: Int, val voiceReceived: Int,
        val textSent: Int, val textReceived: Int, val textDups: Int, val textInOrder: Boolean,
        val error: String? = null
    )

    var onProgress: ((String) -> Unit)? = null
    var onIncomingInvite: (suspend (fromIp: String, id: String) -> Boolean)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var masterPeerIp: String? = null
    @Volatile private var testId: String = ""
    @Volatile private var masterMode = false

    private val pongs = ConcurrentHashMap<Long, Long>()
    private var acceptWaiter = CompletableDeferred<Boolean>()
    private var voiceDone = CompletableDeferred<Int>()
    private var textDone = CompletableDeferred<String>()

    private val slaveVoiceCount = AtomicInteger(0)
    private val slaveTextOrder = mutableListOf<String>()

    fun onIncoming(fromIp: String, payload: String) {
        val p = TestPacket.parse(payload) ?: return
        if (p.t != "voice" && p.t != "text") {
            InAppLogger.write('D', "PeerTest", "rx ${p.t} from $fromIp seq=${p.seq} master=$masterMode")
        }
        when (p.t) {
            "invite" -> handleInvite(fromIp, p)
            "accept" -> if (masterMode && !acceptWaiter.isCompleted) {
                InAppLogger.write('I', "PeerTest", "accept received → go")
                acceptWaiter.complete(true)
            }
            "decline" -> if (masterMode && !acceptWaiter.isCompleted) {
                InAppLogger.write('I', "PeerTest", "decline received")
                acceptWaiter.complete(false)
            }
            "ping" -> sendTest(fromIp, p.id, "pong", p.seq)
            "pong" -> pongs[p.seq] = System.currentTimeMillis()
            "voice" -> slaveVoiceCount.incrementAndGet()
            "voice_done_req" -> sendTest(fromIp, p.id, "voice_done", 0, slaveVoiceCount.get().toString())
            "text" -> synchronized(slaveTextOrder) { slaveTextOrder.add(p.data ?: "") }
            "text_done_req" -> {
                val json = JSONObject().apply {
                    put("received", slaveTextOrder.size)
                    put("inOrder", checkOrder())
                    put("dups", slaveTextOrder.size - slaveTextOrder.toSet().size)
                }.toString()
                sendTest(fromIp, p.id, "text_done", 0, json)
            }
            "voice_done" -> if (masterMode && !voiceDone.isCompleted) voiceDone.complete(p.data?.toIntOrNull() ?: -1)
            "text_done" -> if (masterMode && !textDone.isCompleted) textDone.complete(p.data ?: "{}")
        }
    }

    private fun checkOrder(): Boolean {
        val expected = (1..slaveTextOrder.size).map { "MSG$it" }
        return slaveTextOrder == expected
    }

    private fun handleInvite(fromIp: String, p: TestPacket) {
        InAppLogger.write('I', "PeerTest", "invite from $fromIp id=${p.id} master=$masterMode")
        // Race-fix: если мы уже master — отказываем
        if (masterMode) {
            InAppLogger.write('W', "PeerTest", "already master → decline")
            sendTest(fromIp, p.id, "decline")
            return
        }
        scope.launch {
            val accepted = try {
                withTimeoutOrNull(60_000) {
                    onIncomingInvite?.invoke(fromIp, p.id) ?: false
                } ?: false
            } catch (e: CancellationException) {
                false
            } catch (e: Exception) {
                InAppLogger.write('E', "PeerTest", "onIncomingInvite failed", e)
                false
            }
            if (accepted) {
                InAppLogger.write('I', "PeerTest", "user accepted → send accept")
                synchronized(slaveTextOrder) { slaveTextOrder.clear() }
                slaveVoiceCount.set(0)
                sendTest(fromIp, p.id, "accept")
            } else {
                InAppLogger.write('I', "PeerTest", "user declined / timeout → send decline")
                sendTest(fromIp, p.id, "decline")
            }
        }
    }

    private fun sendTest(ip: String, id: String, type: String, seq: Long = 0L, data: String? = null) {
        if (type != "voice" && type != "text") {
            InAppLogger.write('D', "PeerTest", "tx $type seq=$seq to $ip")
        }
        mesh.sendTestPacket(ip, TestPacket(type, id, seq, data).toJson())
    }

    suspend fun runAsMaster(peerIp: String): Report = coroutineScope {
        masterMode = true
        masterPeerIp = peerIp
        testId = UUID.randomUUID().toString()
        acceptWaiter = CompletableDeferred()
        voiceDone = CompletableDeferred()
        textDone = CompletableDeferred()
        pongs.clear()

        val rtts = mutableListOf<Long>()

        try {
            onProgress?.invoke("Приглашаем пира…")
            sendTest(peerIp, testId, "invite")
            val accepted = withTimeoutOrNull(65_000) { acceptWaiter.await() } ?: false
            if (!accepted) return@coroutineScope Report(false, 0, 0, 0, 0, 0, 0, 0, 0, false, "Пир не принял приглашение")

            onProgress?.invoke("Рукопожатие (5 пакетов)…")
            for (i in 1..5L) {
                pongs.remove(i)
                sendTest(peerIp, testId, "ping", i)
                val t0 = System.currentTimeMillis()
                val pongAt = withTimeoutOrNull(3000) {
                    while (true) { pongs[i]?.let { return@withTimeoutOrNull it }; delay(30) }
                    null
                }
                if (pongAt != null) rtts.add(pongAt - t0)
                delay(100)
            }
            if (rtts.size < 3) return@coroutineScope Report(false, 0, 0, 0, 0, 0, 0, 0, 0, false, "PONG: ${rtts.size}/5")

            onProgress?.invoke("Голос: 100 пакетов…")
            for (i in 1..100L) sendTest(peerIp, testId, "voice", i, "V$i")
            sendTest(peerIp, testId, "voice_done_req")
            val vRecv = withTimeoutOrNull(5000) { voiceDone.await() } ?: -1
            if (vRecv < 0) return@coroutineScope Report(false, rtts.min(), rtts.average().toLong(), rtts.max(), 100, 0, 0, 0, 0, false, "Voice: нет ответа")

            onProgress?.invoke("Текст: 10 сообщений…")
            for (i in 1..10L) { sendTest(peerIp, testId, "text", i, "MSG$i"); delay(30) }
            sendTest(peerIp, testId, "text_done_req")
            val tJson = withTimeoutOrNull(5000) { textDone.await() }
                ?: return@coroutineScope Report(false, rtts.min(), rtts.average().toLong(), rtts.max(), 100, vRecv, 10, 0, 0, false, "Text: нет ответа")
            val tObj = JSONObject(tJson)

            onProgress?.invoke("Готово")
            Report(
                success = true,
                rttMin = rtts.min(), rttAvg = rtts.average().toLong(), rttMax = rtts.max(),
                voiceSent = 100, voiceReceived = vRecv,
                textSent = 10, textReceived = tObj.optInt("received", -1),
                textDups = tObj.optInt("dups", 0), textInOrder = tObj.optBoolean("inOrder", false)
            )
        } catch (e: Exception) {
            Report(false, 0, 0, 0, 0, 0, 0, 0, 0, false, "Exception: ${e.message}")
        } finally {
            masterMode = false
        }
    }
}
