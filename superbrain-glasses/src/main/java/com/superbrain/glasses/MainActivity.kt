package com.superbrain.glasses

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * SuperBrain Glasses — HUD Activity.
 * Pure display layer: starts Service, binds to it, renders Compose UI.
 * Service owns all resources (WebSocket, Camera, Audio, etc.).
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 100
    }

    private var service: SuperBrainService? = null
    private val fallbackState = MutableStateFlow(HudState(statusText = "Starting..."))

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.i(TAG, "Bound to SuperBrainService")
            service = (binder as SuperBrainService.LocalBinder).service
            // Re-set content with real state from Service
            setContent {
                HudScreen(service!!.hudState)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "SuperBrainService disconnected")
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Request permissions before starting Service
        requestPermissionsIfNeeded()

        // Start the foreground service
        SuperBrainService.start(this)

        // Bind to service for state observation
        bindService(
            Intent(this, SuperBrainService::class.java),
            connection,
            BIND_AUTO_CREATE
        )

        // Show fallback UI until Service binds
        setContent {
            HudScreen(fallbackState)
        }

        Log.i(TAG, "MainActivity started, binding to Service")
    }

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unbindService(connection) } catch (_: Exception) {}
        service = null
        Log.i(TAG, "MainActivity destroyed (Service continues running)")
    }
}
