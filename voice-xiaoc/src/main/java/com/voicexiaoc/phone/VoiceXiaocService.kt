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
    lateinit var audio: AudioCapture; private set

    // Last reply text surfaced to the UI.
    private val _lastReply = MutableStateFlow("")
    val lastReply: StateFlow<String> = _lastReply.asStateFlow()

    // Push-to-talk voice pipeline state (P2a).
    private val _voiceState = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    private var asr: TencentAsrClient? = null
    private val finals = StringBuilder()     // accumulated finalized sentences
    private var finishGuard: Job? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "onCreate")

        config = ConfigStore(this)
        tts = TtsPlayer(this)
        audio = AudioCapture(this)
        ws = WsClient(scope).apply {
            host = config.host
            port = config.port
            token = config.token
            deviceId = "phone-${Build.MODEL}".replace(" ", "_")
        }
        audio.onLog = { level, msg -> ws.sendLog(level, "AudioCapture", msg) }
        installCrashReporter()
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
                _voiceState.value = VoiceState.Reply(r.text)
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
        finishGuard?.cancel()
        audio.cleanup()
        asr?.cancel(); asr = null
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

    val isListening: Boolean get() = asr != null

    /** UI toggle: start listening if idle, otherwise stop and submit. */
    fun toggleListening() {
        if (isListening) stopListening() else startListening()
    }

    /**
     * P2a "simulated wake": user taps the button → open mic, stream PCM to
     * Tencent streaming ASR. Interim/final transcripts drive [voiceState].
     * The accumulated text is sent to the gateway when the user stops (or the
     * server closes the stream).
     */
    fun startListening() {
        if (isListening) return
        ws.sendLog("info", "VoiceService", "startListening tapped")
        if (!config.asrConfigured) {
            _voiceState.value = VoiceState.Error("未配置腾讯 ASR 凭据（local.properties: tencent.*）")
            ws.sendLog("error", "VoiceService", "ASR credentials missing")
            return
        }
        val hasMic = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ws.sendLog("info", "VoiceService", "RECORD_AUDIO granted=$hasMic")
        // Report a wake event to the gateway and stop any ongoing TTS (avoid echo).
        ws.sendWake("小C", 1.0)
        tts.stop()

        finals.setLength(0)
        _voiceState.value = VoiceState.Listening

        val client = TencentAsrClient(
            secretId = config.asrSecretId,
            secretKey = config.asrSecretKey,
            appId = config.asrAppId,
            engine = "16k_zh",
            voiceFormat = 1,   // raw PCM 16k mono from AudioCapture
        )
        asr = client
        client.start(object : TencentAsrClient.Listener {
            override fun onReady() {
                ws.sendLog("info", "TencentAsr", "handshake ok — streaming mic PCM")
                audio.startPcm(scope) { pcm -> client.sendPcm(pcm) }
            }
            override fun onPartial(text: String) {
                ws.sendLog("debug", "TencentAsr", "partial: $text")
                _voiceState.value = VoiceState.Recognizing(finals.toString() + text)
            }
            override fun onFinal(text: String) {
                ws.sendLog("info", "TencentAsr", "final sentence: $text")
                finals.append(text)
                _voiceState.value = VoiceState.Recognizing(finals.toString())
            }
            override fun onCompleted() {
                ws.sendLog("info", "TencentAsr", "stream completed")
                finalizeAndSend()
            }
            override fun onError(msg: String) {
                ws.sendLog("error", "TencentAsr", "error: $msg")
                audio.stop()
                asr = null
                _voiceState.value = VoiceState.Error(msg)
            }
        })
    }

    /** Stop capturing and flush the ASR stream; result is submitted on completion. */
    fun stopListening() {
        val client = asr ?: return
        ws.sendLog("info", "VoiceService", "stopListening tapped")
        audio.stop()
        client.finish()
        // Guard: if the server never sends final=1, force-submit what we have.
        finishGuard?.cancel()
        finishGuard = scope.launch {
            kotlinx.coroutines.delay(3000)
            if (asr === client) {
                ws.sendLog("warn", "VoiceService", "finish guard fired — submitting accumulated text")
                finalizeAndSend()
            }
        }
    }

    private fun finalizeAndSend() {
        finishGuard?.cancel(); finishGuard = null
        audio.stop()
        asr?.cancel()
        asr = null
        val text = finals.toString().trim()
        if (text.isEmpty()) {
            _voiceState.value = VoiceState.Idle
            ws.sendLog("warn", "VoiceService", "ASR produced no text")
            return
        }
        ws.sendLog("info", "VoiceService", "ASR final -> gateway: $text")
        _voiceState.value = VoiceState.Sent(text)
        _lastReply.value = ""
        ws.sendAsrText(text)
    }

    private fun installCrashReporter() {
        val prefs = getSharedPreferences("voicexiaoc_crash", Context.MODE_PRIVATE)
        prefs.getString("last_crash", null)?.let { crash ->
            prefs.edit().remove("last_crash").apply()
            scope.launch {
                kotlinx.coroutines.delay(3000) // let ws connect first
                ws.sendLog("error", "CrashReporter", "previous run crashed: $crash")
            }
        }
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val trace = Log.getStackTraceString(throwable).take(2000)
                prefs.edit().putString("last_crash", "${throwable.javaClass.name}: ${throwable.message}\n$trace").commit()
            } catch (_: Exception) { }
            default?.uncaughtException(thread, throwable)
        }
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
