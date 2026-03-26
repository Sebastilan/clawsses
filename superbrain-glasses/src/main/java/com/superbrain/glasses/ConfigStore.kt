package com.superbrain.glasses

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Persistent configuration store using SharedPreferences.
 * Survives app restarts and reboots.
 */
class ConfigStore(context: Context) {

    companion object {
        private const val TAG = "ConfigStore"
        private const val PREFS_NAME = "superbrain_config"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"
        private const val KEY_AUTO_CONNECT = "auto_connect"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString(KEY_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HOST, value).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, 8011)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var autoConnect: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CONNECT, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CONNECT, value).apply()

    val isConfigured: Boolean
        get() = host.isNotBlank() && token.isNotBlank()

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
        "host=$host:$port, autoConnect=$autoConnect, configured=$isConfigured"
}
