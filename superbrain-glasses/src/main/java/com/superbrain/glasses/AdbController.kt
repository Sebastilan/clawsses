package com.superbrain.glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BroadcastReceiver handling all ADB-sent intents.
 * Registered on SuperBrainService (survives Activity death).
 * Action prefix: com.superbrain.glasses.*
 */
class AdbController : BroadcastReceiver() {

    companion object {
        private const val TAG = "AdbController"
        private const val PREFIX = "com.superbrain.glasses."
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (!action.startsWith(PREFIX)) return

        val command = action.removePrefix(PREFIX)
        Log.i(TAG, "ADB command: $command")

        val service = SuperBrainService.instance ?: run {
            Log.e(TAG, "SuperBrainService not running")
            return
        }

        when (command) {
            "CONFIG" -> {
                val host = intent.getStringExtra("host") ?: ""
                val port = intent.getIntExtra("port", 8011)
                val token = intent.getStringExtra("token") ?: ""
                service.handleConfig(host, port, token)
            }
            "CONNECT" -> service.handleConnect()
            "DISCONNECT" -> service.handleDisconnect()
            "SEND" -> {
                val text = intent.getStringExtra("text") ?: ""
                if (text.isNotBlank()) service.handleSend(text)
            }
            "PHOTO" -> service.handlePhoto()
            "LISTEN_START" -> service.handleListenStart()
            "LISTEN_STOP" -> service.handleListenStop()
            "DISPLAY" -> {
                val text = intent.getStringExtra("text") ?: ""
                if (text.isNotBlank()) service.handleDisplay(text)
            }
            "STATUS" -> service.handleStatus()
            "OTA" -> {
                val url = intent.getStringExtra("url") ?: ""
                if (url.isNotBlank()) service.handleOta(url)
            }
            "WIFI" -> {
                val ssid = intent.getStringExtra("ssid") ?: ""
                val password = intent.getStringExtra("password") ?: ""
                if (ssid.isNotBlank()) service.handleWifi(ssid, password)
            }
            "WIFI_STATUS" -> service.handleWifiStatus()
            "WAKE_ENABLE" -> service.handleWakeEnable()
            "WAKE_DISABLE" -> service.handleWakeDisable()
            "ENROLL_START" -> service.handleEnrollStart()
            "ENROLL_CLEAR" -> service.handleEnrollClear()
            else -> Log.w(TAG, "Unknown command: $command")
        }
    }
}
