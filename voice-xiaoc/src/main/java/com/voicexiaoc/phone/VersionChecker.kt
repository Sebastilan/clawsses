package com.voicexiaoc.phone

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Startup version self-check for silent OTA.
 *
 * Fetches a remote [manifest][VersionManifest] (version.json) and, if its
 * `versionCode` is newer than the locally installed one, hands the `apkUrl`
 * to [OtaUpdater] to download + install — no user tap required. On Android 12+
 * the OS still shows an install-confirmation dialog at commit time (unavoidable).
 */
class VersionChecker(
    private val localVersionCode: Int,
    private val ota: OtaUpdater,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "VersionChecker"
    }

    /** Remote manifest schema. Kept small and self-describing. */
    data class VersionManifest(
        val versionCode: Int = 0,
        val versionName: String = "",
        val apkUrl: String = "",
        val changelog: String = ""
    )

    sealed class State {
        object Idle : State()
        object Checking : State()
        object UpToDate : State()
        data class UpdateAvailable(val manifest: VersionManifest) : State()
        data class Updating(val progress: String) : State()
        data class Failed(val reason: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Check [versionUrl] and auto-trigger OTA when a newer build exists.
     * @param autoInstall when true, immediately starts the download+install.
     */
    fun check(versionUrl: String, autoInstall: Boolean = true) {
        scope.launch(Dispatchers.IO) {
            _state.value = State.Checking
            val manifest = try {
                val req = Request.Builder().url(versionUrl).build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                    val body = resp.body?.string() ?: throw Exception("empty body")
                    gson.fromJson(body, VersionManifest::class.java)
                }
            } catch (e: Exception) {
                Log.e(TAG, "version check failed: ${e.message}")
                _state.value = State.Failed("check failed: ${e.message}")
                return@launch
            }

            Log.i(TAG, "local=$localVersionCode remote=${manifest.versionCode} url=${manifest.apkUrl}")
            if (manifest.versionCode <= localVersionCode || manifest.apkUrl.isBlank()) {
                _state.value = State.UpToDate
                return@launch
            }

            _state.value = State.UpdateAvailable(manifest)
            if (autoInstall) {
                withContext(Dispatchers.Main) {
                    ota.startUpdate(manifest.apkUrl) { progress ->
                        _state.value = State.Updating(progress)
                    }
                }
            }
        }
    }
}
