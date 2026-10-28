package com.mesh.core

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FileTransferManager(
    private val scope: CoroutineScope,
    private val contentResolver: ContentResolver,
    private val saveDir: File,
    private val onProgress: (fileId: String, name: String, sent: Long, total: Long, done: Boolean, error: String?) -> Unit,
    private val onIncomingOffer: suspend (fileId: String, name: String, size: Long, fromIp: String) -> Boolean,
    private val logger: MeshLogger = AndroidMeshLogger
) {
    companion object {
        const val PORT = 47821
        private const val MAGIC = 0x5A46494C  // 'ZFIL'
        private const val CHUNK = 32 * 1024
    }

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    @Volatile private var serverStopped = true

    fun startServer(bindIp: String) {
        stopServer()
        serverStopped = false
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(InetAddress.getByName(bindIp), PORT))
            serverSocket = ss
            serverJob = scope.launch(Dispatchers.IO) {
                while (isActive && !serverStopped) {
                    try {
                        val sock = ss.accept()
                        if (serverStopped) { runCatching { sock.close() }; break }
                        launch(Dispatchers.IO) { handleIncoming(sock) }
                    } catch (e: Exception) {
                        if (serverStopped || !isActive || ss.isClosed) break
                        logger.w("accept: ${e.message}")
                    }
                }
            }
            logger.d("TCP file server on $bindIp:$PORT")
        } catch (e: Exception) { logger.e("TCP server failed", e); serverStopped = true }
    }

    fun stopServer() {
        serverStopped = true
        serverJob?.cancel(); serverJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private suspend fun handleIncoming(sock: Socket) = coroutineScope {
        try {
            sock.soTimeout = 30_000
            val input = DataInputStream(BufferedInputStream(sock.getInputStream()))
            val magic = input.readInt()
            if (magic != MAGIC) { sock.close(); return@coroutineScope }
            val nameLen = input.readInt()
            if (nameLen <= 0 || nameLen > 1024) { sock.close(); return@coroutineScope }
            val nameBytes = ByteArray(nameLen); input.readFully(nameBytes)
            val name = String(nameBytes, Charsets.UTF_8)
            val size = input.readLong()
            val fileId = input.readUTF()
            val fromIp = sock.inetAddress.hostAddress ?: "?"

            val accept = onIncomingOffer(fileId, name, size, fromIp)
            if (!accept) { sock.close(); return@coroutineScope }

            saveDir.mkdirs()
            val safeName = name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
            val outFile = File(saveDir, "${System.currentTimeMillis()}_$safeName")
            var received = 0L
            FileOutputStream(outFile).use { fos ->
                val buf = ByteArray(CHUNK)
                while (received < size) {
                    val toRead = minOf(buf.size.toLong(), size - received).toInt()
                    val n = input.read(buf, 0, toRead)
                    if (n <= 0) break
                    fos.write(buf, 0, n)
                    received += n
                    onProgress(fileId, name, received, size, false, null)
                }
            }
            onProgress(fileId, name, received, size, true, null)
            logger.d("received $name ($received/$size) → ${outFile.absolutePath}")
            sock.close()
        } catch (e: Exception) {
            logger.e("handleIncoming", e)
            runCatching { sock.close() }
        }
    }

    fun sendFile(uri: Uri, toIp: String) {
        scope.launch(Dispatchers.IO) {
            val fileId = java.util.UUID.randomUUID().toString()
            try {
                var name = "file"
                var size = 0L
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idxName = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val idxSize = c.getColumnIndex(OpenableColumns.SIZE)
                        if (idxName >= 0) name = c.getString(idxName)
                        if (idxSize >= 0) size = c.getLong(idxSize)
                    }
                }
                if (size <= 0) { onProgress(fileId, name, 0, 0, true, "unknown size"); return@launch }

                // 1. Connect
                val sock = Socket()
                sock.connect(InetSocketAddress(InetAddress.getByName(toIp), PORT), 15_000)
                val out = DataOutputStream(BufferedOutputStream(sock.getOutputStream()))
                out.writeInt(MAGIC)
                val nameBytes = name.toByteArray(Charsets.UTF_8)
                out.writeInt(nameBytes.size); out.write(nameBytes)
                out.writeLong(size)
                out.writeUTF(fileId)
                out.flush()

                // 2. Stream file
                val input = contentResolver.openInputStream(uri) ?: run {
                    sock.close(); onProgress(fileId, name, 0, size, true, "cannot open"); return@launch
                }
                input.use { ins ->
                    val buf = ByteArray(CHUNK)
                    var sent = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        sent += n
                        onProgress(fileId, name, sent, size, false, null)
                    }
                    out.flush()
                }
                sock.close()
                onProgress(fileId, name, size, size, true, null)
                logger.d("sent $name to $toIp")
            } catch (e: Exception) {
                logger.e("sendFile", e)
                onProgress(fileId, "", 0, 0, true, e.message ?: "error")
            }
        }
    }

    fun close() = stopServer()
}
