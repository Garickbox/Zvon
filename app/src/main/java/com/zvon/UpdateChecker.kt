package com.zvon

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.mesh.core.InAppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {
    private const val REPO = "Garickbox/Zvon"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"

    data class UpdateInfo(
        val versionCode: Int,
        val tagName: String,
        val apkUrl: String,
        val apkSize: Long,
        val releaseName: String
    )

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            InAppLogger.write('I', "UpdateChecker", "checking $API_URL")
            val conn = URL(API_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "Zvon-Android")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            if (conn.responseCode != 200) {
                InAppLogger.write('W', "UpdateChecker", "http ${conn.responseCode}")
                return@withContext null
            }

            val json = conn.inputStream.bufferedReader().use { it.readText() }
            val release = JSONObject(json)
            val tag = release.optString("tag_name", "")
            val versionCode = tag.removePrefix("build-").toIntOrNull()
                ?: return@withContext null

            val assets = release.optJSONArray("assets") ?: return@withContext null
            var apkUrl: String? = null
            var apkSize = 0L
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.optString("browser_download_url")
                    apkSize = a.optLong("size", 0)
                    break
                }
            }
            if (apkUrl.isNullOrBlank()) {
                InAppLogger.write('W', "UpdateChecker", "no apk asset in release")
                return@withContext null
            }

            InAppLogger.write('I', "UpdateChecker", "latest=$versionCode tag=$tag size=$apkSize")
            UpdateInfo(versionCode, tag, apkUrl, apkSize, release.optString("name", tag))
        } catch (e: Exception) {
            InAppLogger.write('W', "UpdateChecker", "check failed: ${e.message}")
            null
        }
    }

    suspend fun downloadApk(
        context: Context,
        info: UpdateInfo,
        onProgress: (received: Long, total: Long) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, "apk").apply { mkdirs() }
            val file = File(dir, "zvon-${info.versionCode}.apk")
            if (file.exists() && file.length() == info.apkSize && info.apkSize > 0) {
                InAppLogger.write('I', "UpdateChecker", "apk cached: ${file.absolutePath}")
                return@withContext file
            }
            file.delete()

            InAppLogger.write('I', "UpdateChecker", "downloading ${info.apkUrl}")
            val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Zvon-Android")
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            if (conn.responseCode != 200) {
                InAppLogger.write('E', "UpdateChecker", "download http ${conn.responseCode}")
                return@withContext null
            }

            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.apkSize
            var received = 0L
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        received += n
                        onProgress(received, total)
                    }
                }
            }
            InAppLogger.write('I', "UpdateChecker", "downloaded $received bytes")
            if (received < 1024 * 100) {
                InAppLogger.write('E', "UpdateChecker", "file too small, discard")
                file.delete()
                return@withContext null
            }
            file
        } catch (e: Exception) {
            InAppLogger.write('E', "UpdateChecker", "download failed", e)
            null
        }
    }

    fun canInstall(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 26) {
            context.packageManager.canRequestPackageInstalls()
        } else true
    }

    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                InAppLogger.write('E', "UpdateChecker", "open unknown sources failed", e)
            }
        }
    }

    fun installApk(context: Context, file: File): Boolean {
        return try {
            if (!canInstall(context)) {
                InAppLogger.write('W', "UpdateChecker", "install permission not granted")
                return false
            }
            val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            InAppLogger.write('I', "UpdateChecker", "install intent sent")
            true
        } catch (e: Exception) {
            InAppLogger.write('E', "UpdateChecker", "install failed", e)
            false
        }
    }
}
