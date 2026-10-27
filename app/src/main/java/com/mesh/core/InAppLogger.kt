package com.mesh.core

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object InAppLogger {
    private const val MAX_SIZE = 5L * 1024 * 1024
    private val lock = Any()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private var logFile: File? = null
    private var stream: FileOutputStream? = null
    @Volatile private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            try {
                val dir = File(context.getExternalFilesDir(null), "logs")
                dir.mkdirs()
                val f = File(dir, "zvon.log")
                if (f.exists() && f.length() > MAX_SIZE) {
                    val old = File(dir, "zvon.1.log")
                    old.delete()
                    f.renameTo(old)
                }
                logFile = f
                stream = FileOutputStream(f, true)
                initialized = true
                write('I', "Logger", "=== Session ${dateFmt.format(Date())} ${timeFmt.format(Date())} ===")
            } catch (e: Exception) {
                android.util.Log.e("ZvonLogger", "init failed", e)
            }
        }
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
                    append('\n').append("  ")
                    append(tr.stackTraceToString().replace("\n", "\n  "))
                }
                append('\n')
            }
            try { s.write(line.toByteArray()) } catch (_: Exception) { }
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
