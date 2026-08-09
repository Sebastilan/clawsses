package com.voicexiaoc.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the gateway WebSocket alive across screen-off /
 * lock-screen, owns the shared components (WsClient, TtsPlayer), and receives
 * PackageInstaller status callbacks for OTA.
 *
 * Mirrors superbrain-glasses' service architecture: the Activity is a thin UI
 * layer that binds here; the service owns all long-lived resources.
 */
class VoiceXiaocService : Service() {

    companion object {
        private const val TAG = "VoiceXiaocService"
        private const val CHANNEL_ID = "voicexiaoc"
        private const val NOTIF_ID = 1
        private const val ACTION_INSTALL_STATUS = "com.voicexiaoc.phone.INSTALL_STATUS"

        @Volatile var instance: VoiceXiaocService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, VoiceXiaocService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    inner class LocalBinder : Binder() {
        val service: VoiceXiaocService get() = this@VoiceXiaocService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null

    lateinit var config: ConfigStore; private set
    lateinit var ws: WsClient; private set
    lateinit var tts: TtsPlayer; private set
    lateinit var ota: OtaUpdater; private set
    lateinit var versionChecker: VersionChecker; private set

    // Last reply text surfaced to the UI.
    private val _lastReply = MutableStateFlow("")
    val lastReply: StateFlow<String> = _lastReply.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "onCreate")

        config = ConfigStore(this)
        tts = TtsPlayer(this)
        ws = WsClient(scope).apply {
            host = config.host
            port = config.port
            token = config.token
            deviceId = "phone-${Build.MODEL}".replace(" ", "_")
        }
        ota = OtaUpdater(this, scope)
        val localCode = try {
            packageManager.getPackageInfo(packageName, 0).let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode.toInt() else @Suppress("DEPRECATION") it.versionCode
            }
        } catch (e: Exception) { BuildConfig.VERSION_CODE }
        versionChecker = VersionChecker(localCode, ota, scope)

        createChannel()
        startForeground(NOTIF_ID, buildNotification("语音小C 启动中…"))
        acquireWakeLock()

        // Route gateway replies to on-device TTS + UI state.
        scope.launch {
            ws.replies.collect { r ->
                _lastReply.value = r.text
                tts.speak(r.text)
            }
        }
        scope.launch {
            ws.ttsAudio.collect { a -> tts.playBase64(a.base64, a.format) }
        }
        scope.launch {
            ws.status.collect { s -> updateNotification(s) }
        }

        if (config.autoConnect) ws.connect()

        // Startup version self-check → silent OTA when a newer build exists.
        versionChecker.check(config.versionUrl, autoInstall = true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_INSTALL_STATUS) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
            val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { startActivity(confirm) } catch (e: Exception) { Log.e(TAG, "confirm intent failed", e) }
                }
                PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "OTA install success")
                else -> Log.w(TAG, "OTA install status=$status msg=$msg")
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy")
        ws.disconnect()
        tts.cleanup()
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        instance = null
    }

    /** Public entry for the UI: send a typed/recognized utterance to the gateway. */
    fun sendText(text: String) {
        if (text.isBlank()) return
        ws.sendAsrText(text)
    }

    fun reconnect() {
        ws.host = config.host; ws.port = config.port; ws.token = config.token
        ws.connect()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voicexiaoc:ws").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "语音小C", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setContentTitle("语音小C")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}
