package com.voicexiaoc.phone

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * OTA updater: downloads an APK from a URL (OSS) and installs it via
 * PackageInstaller. Works on Android 12+ without root using a session install.
 *
 * Ported verbatim (mechanism-wise) from superbrain-glasses/OtaUpdater.kt.
 * On Android 12+ the commit triggers the system install-confirmation dialog —
 * that is an OS restriction, not something the app can suppress; VersionChecker
 * surfaces a UI hint before this runs.
 */
class OtaUpdater(private val context: Context, private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "OtaUpdater"
        private const val APK_FILENAME = "voicexiaoc-update.apk"
        private const val MAX_RETRIES = 1
    }

    var isUpdating = false
        private set

    private var onProgress: ((String) -> Unit)? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun startUpdate(url: String, progressCallback: (String) -> Unit) {
        if (isUpdating) {
            progressCallback("OTA already in progress")
            return
        }
        isUpdating = true
        onProgress = progressCallback

        scope.launch(Dispatchers.IO) {
            var retries = 0
            var success = false
            while (retries <= MAX_RETRIES && !success) {
                try {
                    if (retries > 0) {
                        withContext(Dispatchers.Main) { progressCallback("Retrying download…") }
                    }
                    val apkFile = downloadApk(url)
                    withContext(Dispatchers.Main) { progressCallback("Download complete. Installing…") }
                    installApk(apkFile)
                    success = true
                } catch (e: Exception) {
                    Log.e(TAG, "OTA attempt ${retries + 1} failed", e)
                    retries++
                    if (retries > MAX_RETRIES) {
                        withContext(Dispatchers.Main) { progressCallback("OTA failed: ${e.message}") }
                    }
                }
            }
            isUpdating = false
        }
    }

    private suspend fun downloadApk(url: String): File {
        Log.i(TAG, "Downloading APK from $url")
        withContext(Dispatchers.Main) { onProgress?.invoke("Downloading APK…") }

        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) throw Exception("Download failed: HTTP ${response.code}")

        val body = response.body ?: throw Exception("Empty response body")
        val contentLength = body.contentLength()
        val apkFile = File(context.cacheDir, APK_FILENAME)

        FileOutputStream(apkFile).use { output ->
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                var totalRead = 0L
                var lastPercent = -1
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    totalRead += read
                    if (contentLength > 0) {
                        val percent = (totalRead * 100 / contentLength).toInt()
                        if (percent != lastPercent && percent % 10 == 0) {
                            lastPercent = percent
                            withContext(Dispatchers.Main) { onProgress?.invoke("Downloading: $percent%") }
                        }
                    }
                }
            }
        }
        Log.i(TAG, "Downloaded APK: ${apkFile.length()} bytes")
        return apkFile
    }

    private fun installApk(apkFile: File) {
        Log.i(TAG, "Installing APK via PackageInstaller")
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setSize(apkFile.length())

        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)
        try {
            session.openWrite("voicexiaoc-update", 0, apkFile.length()).use { output ->
                apkFile.inputStream().use { input -> input.copyTo(output) }
                session.fsync(output)
            }
            val intent = Intent(context, VoiceXiaocService::class.java).apply {
                action = "com.voicexiaoc.phone.INSTALL_STATUS"
            }
            val pendingIntent = PendingIntent.getService(
                context, sessionId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(pendingIntent.intentSender)
            Log.i(TAG, "Install session committed")
        } catch (e: Exception) {
            session.abandon()
            throw e
        } finally {
            apkFile.delete()
        }
    }
}
