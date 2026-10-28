package com.mesh.core

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object InAppLogger {
    private const val MAX_SIZE = 1L * 1024 * 1024   // 1 МБ на файл
    private const val MAX_ROTATED = 2               // zvon.1.log, zvon.2.log
    private const val CHECK_EVERY = 500             // проверять размер каждые N записей

    private val lock = Any()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private var logDir: File? = null
    private var logFile: File? = null
    private var stream: FileOutputStream? = null
    private var writesSinceCheck = 0
    @Volatile private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            try {
                val dir = File(context.getExternalFilesDir(null), "logs")
                dir.mkdirs()
                logDir = dir
                openNewest()
                initialized = true
                write('I', "Logger", "=== Session ${dateFmt.format(Date())} ${timeFmt.format(Date())} ===")
            } catch (e: Exception) {
                android.util.Log.e("ZvonLogger", "init failed", e)
            }
        }
    }

    private fun openNewest() {
        val dir = logDir ?: return
        val f = File(dir, "zvon.log")
        logFile = f
        stream = FileOutputStream(f, true)
        writesSinceCheck = 0
    }

    private fun rotateLocked() {
        try {
            stream?.close(); stream = null
            val dir = logDir ?: return
            val oldest = File(dir, "zvon.$MAX_ROTATED.log")
            if (oldest.exists()) oldest.delete()
            for (i in MAX_ROTATED - 1 downTo 1) {
                val src = File(dir, "zvon.$i.log")
                if (src.exists()) src.renameTo(File(dir, "zvon.${i + 1}.log"))
            }
            logFile?.renameTo(File(dir, "zvon.1.log"))
            openNewest()
        } catch (_: Exception) { }
    }

    fun write(level: Char, tag: String, msg: String, tr: Throwable? = null) {
        synchronized(lock) {
            val s = stream ?: return
            val now = Date()
            val line = buildString {
                append(dateFmt.format(now)); append(' '); append(timeFmt.format(now))
                append(" [").append(level).append("] ")
                append(tag).append(": ").append(msg)
                if (tr != null) {
                    append('\n').append("  ").append(tr.stackTraceToString().replace("\n", "\n  "))
                }
                append('\n')
            }
            try { s.write(line.toByteArray()) } catch (_: Exception) { }
            writesSinceCheck++
            if (writesSinceCheck >= CHECK_EVERY) {
                writesSinceCheck = 0
                if ((logFile?.length() ?: 0L) > MAX_SIZE) rotateLocked()
            }
        }
    }

    fun flush() { synchronized(lock) { try { stream?.flush() } catch (_: Exception) { } } }
    fun currentFile(): File? = logFile
    fun sizeBytes(): Long = try { logFile?.length() ?: 0L } catch (_: Exception) { 0L }
    fun close() {
        synchronized(lock) {
            try { stream?.close() } catch (_: Exception) { }
            stream = null; initialized = false
        }
    }
}
