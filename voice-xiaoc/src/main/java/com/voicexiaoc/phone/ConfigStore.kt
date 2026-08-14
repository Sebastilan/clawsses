package com.voicexiaoc.phone

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Persistent configuration store (SharedPreferences). Survives app restarts.
 *
 * Ported from superbrain-glasses/ConfigStore.kt, retargeted at the
 * voice-xiaoc-gateway (flat-JSON WebSocket) instead of the OpenClaw gateway.
 *
 * Defaults point at the VPS gateway from voice-xiaoc-gateway/CLAUDE.md
 * (120.26.28.49:8021). All fields are user-overridable at runtime.
 */
class ConfigStore(context: Context) {

    companion object {
        private const val TAG = "ConfigStore"
        private const val PREFS_NAME = "voicexiaoc_config"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"
        private const val KEY_AUTO_CONNECT = "auto_connect"
        private const val KEY_WAKE_ACK = "wake_ack"
        private const val KEY_VERSION_URL = "version_url"
        private const val KEY_ASR_SECRET_ID = "asr_secret_id"
        private const val KEY_ASR_SECRET_KEY = "asr_secret_key"
        private const val KEY_ASR_APPID = "asr_appid"

        // Deployment target VPS gateway (see voice-xiaoc-gateway).
        const val DEFAULT_HOST = "120.26.28.49"
        const val DEFAULT_PORT = 8021
        const val DEFAULT_VERSION_URL =
            "https://lgp-docs.oss-cn-hangzhou.aliyuncs.com/tmp/voice-xiaoc/version.json"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        set(value) = prefs.edit().putString(KEY_HOST, value).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    /** Optional shared secret for the gateway `connect` frame (P1b). Empty = skip. */
    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var autoConnect: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CONNECT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CONNECT, value).apply()

    /**
     * 唤醒后是否应答一声"我在"。默认开 —— 在此之前喊完唤醒词毫无反馈，
     * 开车时看不见屏幕，只能试着说一句看有没有反应。留开关是因为这一声要占
     * 半秒钟，将来若换成更轻的提示音或者他嫌吵，能一键关掉。
     */
    var wakeAck: Boolean
        get() = prefs.getBoolean(KEY_WAKE_ACK, true)
        set(value) = prefs.edit().putBoolean(KEY_WAKE_ACK, value).apply()

    /** URL of the remote version manifest used by VersionChecker for auto-OTA. */
    var versionUrl: String
        get() = prefs.getString(KEY_VERSION_URL, DEFAULT_VERSION_URL) ?: DEFAULT_VERSION_URL
        set(value) = prefs.edit().putString(KEY_VERSION_URL, value).apply()

    // ── Tencent Cloud ASR credentials ────────────────────────────────
    // Defaults come from BuildConfig (injected from local.properties at build
    // time). A runtime override (e.g. rotated key) can be persisted here.

    var asrSecretId: String
        get() = prefs.getString(KEY_ASR_SECRET_ID, null) ?: BuildConfig.TENCENT_SECRET_ID
        set(value) = prefs.edit().putString(KEY_ASR_SECRET_ID, value).apply()

    var asrSecretKey: String
        get() = prefs.getString(KEY_ASR_SECRET_KEY, null) ?: BuildConfig.TENCENT_SECRET_KEY
        set(value) = prefs.edit().putString(KEY_ASR_SECRET_KEY, value).apply()

    var asrAppId: String
        get() = prefs.getString(KEY_ASR_APPID, null) ?: BuildConfig.TENCENT_APPID
        set(value) = prefs.edit().putString(KEY_ASR_APPID, value).apply()

    /** True when ASR credentials are present (from local.properties or override). */
    val asrConfigured: Boolean
        get() = asrSecretId.isNotBlank() && asrSecretKey.isNotBlank() && asrAppId.isNotBlank()

    val wsUrl: String
        get() = "ws://$host:$port"

    fun save(host: String, port: Int, token: String) {
        prefs.edit()
            .putString(KEY_HOST, host)
            .putInt(KEY_PORT, port)
            .putString(KEY_TOKEN, token)
            .putBoolean(KEY_AUTO_CONNECT, true)
            .apply()
        Log.i(TAG, "Config saved: $host:$port, autoConnect=true")
    }

    override fun toString(): String =
        "host=$host:$port, autoConnect=$autoConnect, versionUrl=$versionUrl"
}
