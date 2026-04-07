package com.superbrain.glasses

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Device Owner receiver.
 * Enables DevicePolicyManager.setTime() for NTP-based clock correction.
 *
 * One-time setup after install:
 *   adb shell dpm set-device-owner com.superbrain.glasses/.DeviceAdmin
 */
class DeviceAdmin : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i("DeviceAdmin", "Device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i("DeviceAdmin", "Device admin disabled")
    }
}
