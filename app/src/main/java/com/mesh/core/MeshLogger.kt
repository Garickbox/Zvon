package com.mesh.core

interface MeshLogger {
    fun d(msg: String) = println("[D] $msg")
    fun w(msg: String, t: Throwable? = null) = println("[W] $msg ${t?.let { "| $it" } ?: ""}")
    fun e(msg: String, t: Throwable? = null) = println("[E] $msg ${t?.let { "| $it" } ?: ""}")
}

object AndroidMeshLogger : MeshLogger {
    override fun d(msg: String) {
        android.util.Log.d("MeshCore", msg); InAppLogger.write('D', "MeshCore", msg)
    }
    override fun w(msg: String, t: Throwable?) {
        android.util.Log.w("MeshCore", msg, t); InAppLogger.write('W', "MeshCore", msg, t)
    }
    override fun e(msg: String, t: Throwable?) {
        android.util.Log.e("MeshCore", msg, t); InAppLogger.write('E', "MeshCore", msg, t)
    }
}
