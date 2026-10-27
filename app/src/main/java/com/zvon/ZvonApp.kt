package com.zvon

import android.app.Application
import com.mesh.core.InAppLogger

class ZvonApp : Application() {
    override fun onCreate() {
        super.onCreate()
        InAppLogger.init(this)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            InAppLogger.write('E', "CRASH", "uncaught in ${t.name}: ${e.javaClass.simpleName}", e)
            InAppLogger.flush()
            InAppLogger.close()
            prev?.uncaughtException(t, e) ?: android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}
