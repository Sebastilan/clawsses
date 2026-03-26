package com.superbrain.glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts SuperBrainService on device boot.
 * Only starts if configuration exists (host/token saved).
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val config = ConfigStore(context)
            if (config.isConfigured && config.autoConnect) {
                Log.i(TAG, "Boot completed, starting SuperBrainService (autoConnect=true)")
                SuperBrainService.start(context)
            } else {
                Log.i(TAG, "Boot completed, skipping auto-start (not configured or autoConnect=false)")
            }
        }
    }
}
