package com.superbrain.glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BroadcastReceiver handling all ADB-sent intents.
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

        val app = MainActivity.instance ?: run {
            Log.e(TAG, "MainActivity not running")
            return
        }

        when (command) {
            "CONFIG" -> {
                val host = intent.getStringExtra("host") ?: ""
                val port = intent.getIntExtra("port", 8011)
                val token = intent.getStringExtra("token") ?: ""
                app.handleConfig(host, port, token)
            }
            "CONNECT" -> app.handleConnect()
            "DISCONNECT" -> app.handleDisconnect()
            "SEND" -> {
                val text = intent.getStringExtra("text") ?: ""
                if (text.isNotBlank()) app.handleSend(text)
            }
            "PHOTO" -> app.handlePhoto()
            "LISTEN_START" -> app.handleListenStart()
            "LISTEN_STOP" -> app.handleListenStop()
            "DISPLAY" -> {
                val text = intent.getStringExtra("text") ?: ""
                if (text.isNotBlank()) app.handleDisplay(text)
            }
            "STATUS" -> app.handleStatus()
            else -> Log.w(TAG, "Unknown command: $command")
        }
    }
}
