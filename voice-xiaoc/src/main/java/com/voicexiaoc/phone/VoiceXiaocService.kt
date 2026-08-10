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
        private const val WAKE_WORD = "健康顺利" // local sherpa-onnx KWS keyword, see assets/kws-model/keywords.txt
        // 唤醒后(或答完话的跟进窗口内)等用户开口的时间。这段时间里云端 ASR 是开着的,
        // 车里旁边一直有人说话,窗口越长踩中家人对话、把它当指令上云的概率越高。
        // 2026-08-10 统帅定:8s → 5s。
        private const val WAKE_ARM_TIMEOUT_MS = 5000L  // armed but said nothing at all yet
        private const val ARM_SILENCE_MS = 2500L       // armed, said something, now paused — submit

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
    lateinit var kws: KwsDetector; private set

    // Last reply text surfaced to the UI.
    private val _lastReply = MutableStateFlow("")
    val lastReply: StateFlow<String> = _lastReply.asStateFlow()

    // Push-to-talk voice pipeline state (P2a).
    private val _voiceState = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    private var asr: TencentAsrClient? = null
    private val finals = StringBuilder()     // accumulated finalized sentences
    private var finishGuard: Job? = null

    // Wake-word (P3): continuous ASR session state.
    private var wakeArmed = false            // true = wake word heard, capturing the command sentence
    private var wakeArmTimeout: Job? = null  // "said nothing yet" timeout — falls back to passive listening
    private var armSilenceSubmit: Job? = null // "stopped talking" debounce — submits the accumulated command
    private var wakeSessionActive = false
    private var kwsListening = false         // local KWS mic loop is active (mutually exclusive with command capture)

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "onCreate")

        config = ConfigStore(this)
        tts = TtsPlayer(this)
        audio = AudioCapture(this)
        kws = KwsDetector(this)
        ws = WsClient(scope).apply {
            host = config.host
            port = config.port
            token = config.token
            deviceId = "phone-${Build.MODEL}".replace(" ", "_")
        }
        audio.onLog = { level, msg -> ws.sendLog(level, "AudioCapture", msg) }
        tts.onLog = { level, msg -> ws.sendLog(level, "TtsPlayer", msg) }
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

        // Route gateway replies to UI state. Voice is Doubao-only (tts_audio) —
        // no on-device system TTS fallback; a reply with no audio is just shown
        // silently on screen rather than read out in the wrong (flat) voice.
        scope.launch {
            ws.replies.collect { r ->
                _lastReply.value = r.text
                _voiceState.value = VoiceState.Reply(r.text)
                // Follow-up window: re-arm silently (no wake word needed) so the
                // user can keep talking right after hearing the answer. If they
                // don't say anything, arm()'s own timeout drops back to passive
                // wake-listening.
                scope.launch {
                    kotlinx.coroutines.delay(800)
                    if (wakeSessionActive && !wakeArmed) {
                        stopLocalKwsListening()
                        startCommandCapture()
                        arm(chime = false)
                    }
                }
            }
        }
        scope.launch {
            ws.ttsAudio.collect { a ->
                // Half-duplex: pause the mic for the duration of playback. AEC
                // alone didn't reliably stop the phone hearing its own TTS
                // through the speaker and re-transcribing it as user speech
                // (self-talk echo loop) — the surest fix is to just not listen
                // while we're talking.
                audio.stop()
                tts.playBase64(a.base64, a.format, onDone = {
                    scope.launch {
                        kotlinx.coroutines.delay(300) // let room echo/reverb tail settle
                        resumeMicIfNeeded()
                    }
                })
            }
        }
        scope.launch {
            ws.status.collect { s -> updateNotification(s) }
        }

        if (config.autoConnect) ws.connect()

        // Startup version self-check → silent OTA when a newer build exists.
        versionChecker.check(config.versionUrl, autoInstall = true)

        // P3: mic starts listening continuously right away, passively waiting
        // to hear the wake word — no button tap needed for hands-free use.
        if (config.asrConfigured) startWakeSession()
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
        wakeSessionActive = false
        kwsListening = false
        wakeArmTimeout?.cancel()
        finishGuard?.cancel()
        audio.cleanup()
        asr?.cancel(); asr = null
        kws.release()
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

    /**
     * UI toggle. With the continuous wake session running (the normal case),
     * tapping the button is a manual "force wake" — same effect as saying
     * "小C" — or, if already armed, an early submit of whatever's been said
     * so far. Only falls back to old push-to-talk if the wake session isn't
     * running (e.g. ASR creds missing).
     */
    fun toggleListening() {
        if (wakeSessionActive) {
            if (!wakeArmed) {
                ws.sendLog("info", "VoiceService", "manual tap force-arm")
                stopLocalKwsListening()
                startCommandCapture()
                arm()
            } else {
                submitArmedCommand()
            }
            return
        }
        if (isListening) stopListening() else startListening()
    }

    /**
     * P3 (local wake, 2026-08): mic feeds a local sherpa-onnx KWS spotter
     * continuously — zero network calls until a hit. This is the standard
     * two-stage architecture (local always-on keyword spotting → cloud ASR
     * only after wake), ported from lgp-tv's hun-voice (same model, same
     * "健康顺利" keyword). Replaces the old approach of running continuous
     * cloud ASR and string-matching every final for the wake word.
     */
    private fun startWakeSession() {
        if (wakeSessionActive) return
        wakeSessionActive = true
        wakeArmed = false
        finals.setLength(0)
        _voiceState.value = VoiceState.WakeListening
        startLocalKwsListening()
    }

    /** Local, offline wake-word spotting loop. Mutually exclusive with command capture — only one mic consumer at a time. */
    private fun startLocalKwsListening() {
        if (kwsListening || wakeArmed || asr != null) return
        kwsListening = true
        audio.startPcm(scope) { pcm ->
            val hit = kws.feed(pcm)
            if (hit != null) {
                ws.sendLog("info", "KwsDetector", "local wake hit: $hit")
                stopLocalKwsListening()
                scope.launch { onWakeDetected() }
            }
        }
    }

    private fun stopLocalKwsListening() {
        if (!kwsListening) return
        kwsListening = false
        audio.stop()
    }

    /** Local KWS fired — open a cloud ASR session dedicated to capturing the command that follows. */
    private fun onWakeDetected() {
        startCommandCapture()
        arm()
    }

    /**
     * Open a Tencent ASR session purely for command capture (wake word already
     * confirmed locally, so every final here is real command content — no more
     * text-matching against WAKE_WORD). Closes itself once [submitArmedCommand]
     * fires; the caller restarts local KWS listening afterwards.
     */
    private fun startCommandCapture() {
        if (asr != null) return
        val client = TencentAsrClient(
            secretId = config.asrSecretId,
            secretKey = config.asrSecretKey,
            appId = config.asrAppId,
            engine = "16k_zh",
            voiceFormat = 1,
        )
        asr = client
        client.start(object : TencentAsrClient.Listener {
            override fun onReady() {
                ws.sendLog("info", "TencentAsr", "command capture ready — streaming mic PCM")
                resumeMicIfNeeded()
            }
            override fun onPartial(text: String) {
                _voiceState.value = VoiceState.Recognizing(finals.toString() + text)
                if (text.isNotBlank()) pushSilenceSubmit() // still talking — postpone the submit
            }
            override fun onFinal(text: String) {
                ws.sendLog("info", "TencentAsr", "command final: $text")
                finals.append(text)
                pushSilenceSubmit()
            }
            override fun onCompleted() {
                ws.sendLog("info", "TencentAsr", "command capture completed")
                audio.stop()
                asr = null
                if (wakeArmed) submitArmedCommand()
                else if (wakeSessionActive) startLocalKwsListening()
            }
            override fun onError(msg: String) {
                ws.sendLog("error", "TencentAsr", "command capture error: $msg")
                audio.stop()
                asr = null
                wakeArmed = false
                if (wakeSessionActive) {
                    scope.launch { kotlinx.coroutines.delay(1500); startLocalKwsListening() }
                } else {
                    _voiceState.value = VoiceState.Error(msg)
                }
            }
        })
    }

    /** Re-open the mic if a command-capture session (or local KWS) is alive but paused (e.g. for TTS playback). */
    private fun resumeMicIfNeeded() {
        if (audio.isRecording.value) return
        val client = asr
        if (client != null) {
            audio.startPcm(scope) { pcm -> client.sendPcm(pcm) }
        } else if (wakeSessionActive && !wakeArmed) {
            startLocalKwsListening()
        }
    }

    /** Enter "armed" state: wake heard (or a follow-up window), capturing the next command. */
    private fun arm(chime: Boolean = true) {
        if (chime) {
            ws.sendWake(WAKE_WORD, 1.0)
            tts.stop()
            // Instant "我在" ack so the user knows the wake registered without
            // waiting on a full CC round-trip — a bundled pre-synthesized Doubao
            // clip (zero network latency), not live TTS. Half-duplex: pause the
            // mic for this brief playback so it doesn't get picked up as speech.
            audio.stop()
            tts.playRaw(R.raw.wake_ack, onDone = {
                scope.launch {
                    kotlinx.coroutines.delay(150)
                    resumeMicIfNeeded()
                }
            })
        }
        finals.setLength(0)
        wakeArmed = true
        _voiceState.value = VoiceState.Listening
        armSilenceSubmit?.cancel(); armSilenceSubmit = null
        wakeArmTimeout?.cancel()
        wakeArmTimeout = scope.launch {
            kotlinx.coroutines.delay(WAKE_ARM_TIMEOUT_MS)
            if (wakeArmed && finals.isEmpty()) {
                ws.sendLog("info", "VoiceService", "wake arm timed out (nothing said), back to local KWS listening")
                wakeArmed = false
                audio.stop()
                asr?.cancel(); asr = null
                _voiceState.value = VoiceState.WakeListening
                if (wakeSessionActive) startLocalKwsListening()
            }
        }
    }

    /** (Re)start the "user stopped talking" debounce that triggers [submitArmedCommand]. */
    private fun pushSilenceSubmit() {
        wakeArmTimeout?.cancel() // they've started talking — the "said nothing" timeout no longer applies
        armSilenceSubmit?.cancel()
        armSilenceSubmit = scope.launch {
            kotlinx.coroutines.delay(ARM_SILENCE_MS)
            submitArmedCommand()
        }
    }

    /** Send the accumulated command text (since arming) to the gateway, disarm, and go back to local KWS listening. */
    private fun submitArmedCommand() {
        wakeArmTimeout?.cancel(); wakeArmTimeout = null
        armSilenceSubmit?.cancel(); armSilenceSubmit = null
        val text = finals.toString().trim()
        finals.setLength(0)
        wakeArmed = false
        audio.stop()
        asr?.cancel(); asr = null
        if (text.isBlank()) {
            _voiceState.value = VoiceState.WakeListening
            if (wakeSessionActive) startLocalKwsListening()
            return
        }
        ws.sendLog("info", "VoiceService", "wake command -> gateway: $text")
        _voiceState.value = VoiceState.Sent(text)
        _lastReply.value = ""
        ws.sendAsrText(text)
        if (wakeSessionActive) startLocalKwsListening()
    }

    /**
     * P2a fallback push-to-talk: only used if the continuous wake session
     * isn't running (e.g. ASR creds missing at startup).
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
