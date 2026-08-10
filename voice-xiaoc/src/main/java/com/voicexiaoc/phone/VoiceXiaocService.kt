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
        /**
         * 唤醒词。这是给人看的文案(通知/UI/日志)——真正决定能唤醒什么的是
         * assets/kws-model/keywords.txt 里的拼音词条,改唤醒词两边要一起改。
         * 公开是为了让 UI 引用同一个常量:之前 UI 硬编码"小C",v0.5.0 换词后
         * 界面还在教用户说"小C",而那个词已经唤不醒了。
         */
        const val WAKE_WORD = "健康顺利"
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

    // Wake-word (P3): continuous ASR session state.
    private var wakeArmed = false            // true = wake word heard, capturing the command sentence
    private var wakeArmTimeout: Job? = null  // "said nothing yet" timeout — falls back to passive listening
    private var armSilenceSubmit: Job? = null // "stopped talking" debounce — submits the accumulated command
    private var wakeSessionActive = false

    /**
     * Who the microphone currently belongs to. Exactly one owner at a time.
     *
     * This replaces a `kwsListening` boolean that 9 different call sites could
     * invalidate: 8 of them called `audio.stop()` directly without clearing the
     * flag, so after any TTS playback the flag said "KWS is listening" while the
     * mic was actually closed — and `startLocalKwsListening()`'s guard then
     * early-returned forever, leaving the app deaf to the wake word until some
     * unrelated timeout happened to reset it. Routing every transition through
     * [setMicOwner] makes that class of bug unrepresentable.
     */
    private enum class MicOwner { NONE, KWS, ASR }
    private var micOwner = MicOwner.NONE

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

        // 只检查、不自动装。2026-08-09 曾在统帅夜间开车时把新版本推到他手边这台
        // 唯一的车载语音设备上,系统安装确认框直接弹到屏幕上抢注意力。装新版本
        // 属于改他正在用的设备,必须由他自己按 [installPendingUpdate] 触发。
        versionChecker.check(config.versionUrl, autoInstall = false)

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
        wakeArmTimeout?.cancel()
        armSilenceSubmit?.cancel()
        audio.cleanup()
        asr?.cancel(); asr = null
        kws.release()
        ws.disconnect()
        tts.cleanup()
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        instance = null
    }

    /** 用户主动确认后才安装已发现的新版本(见 onCreate 里为什么不自动装)。 */
    fun installPendingUpdate() {
        val s = versionChecker.state.value
        if (s !is VersionChecker.State.UpdateAvailable) return
        ws.sendLog("info", "VoiceService", "user-confirmed OTA install: v${s.manifest.versionName}")
        ota.startUpdate(s.manifest.apkUrl) { p -> versionChecker.markUpdating(p) }
    }

    /** Public entry for the UI: send a typed/recognized utterance to the gateway. */
    fun sendText(text: String) {
        if (text.isBlank()) return
        ws.sendAsrText(text)
    }

    /**
     * UI toggle — a manual equivalent of saying the wake word (for when it's
     * too loud to be heard, or you'd rather not say it out loud). If already
     * armed, submits early instead of waiting out the silence debounce.
     */
    fun toggleListening() {
        if (!wakeSessionActive) return
        if (wakeArmed) {
            submitArmedCommand()
        } else {
            ws.sendLog("info", "VoiceService", "manual tap force-arm")
            startCommandCapture()
            arm()
        }
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
        setMicOwner(MicOwner.KWS)
    }

    /**
     * The single place the microphone changes hands. Always stops first, so the
     * previous owner's read loop is gone before the next one starts; [micOwner]
     * therefore always describes reality.
     *
     * [MicOwner.KWS]  — local offline wake-word spotting, audio never leaves the phone.
     * [MicOwner.ASR]  — streaming to cloud ASR, only after a confirmed wake.
     * [MicOwner.NONE] — mic closed (idle, or half-duplex pause while we're talking).
     */
    private fun setMicOwner(owner: MicOwner) {
        if (micOwner == owner && audio.isRecording.value) return
        audio.stop()
        micOwner = owner
        when (owner) {
            MicOwner.NONE -> Unit
            MicOwner.KWS -> audio.startPcm(scope) { pcm ->
                kws.feed(pcm)?.let { hit ->
                    ws.sendLog("info", "KwsDetector", "local wake hit: $hit")
                    scope.launch { onWakeDetected() }
                }
            }
            MicOwner.ASR -> {
                val client = asr
                if (client == null) micOwner = MicOwner.NONE
                else audio.startPcm(scope) { pcm -> client.sendPcm(pcm) }
            }
        }
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
                asr = null
                if (wakeArmed) submitArmedCommand()
                else backToWakeListening()
            }
            override fun onError(msg: String) {
                ws.sendLog("error", "TencentAsr", "command capture error: $msg")
                asr = null
                wakeArmed = false
                if (wakeSessionActive) {
                    scope.launch { kotlinx.coroutines.delay(1500); backToWakeListening() }
                } else {
                    setMicOwner(MicOwner.NONE)
                    _voiceState.value = VoiceState.Error(msg)
                }
            }
        })
    }

    /** Drop back to passive local wake-word listening (mic stays on-device). */
    private fun backToWakeListening() {
        wakeArmTimeout?.cancel(); wakeArmTimeout = null
        armSilenceSubmit?.cancel(); armSilenceSubmit = null
        wakeArmed = false
        asr?.cancel(); asr = null
        if (wakeSessionActive) {
            setMicOwner(MicOwner.KWS)
            if (_voiceState.value !is VoiceState.Reply) _voiceState.value = VoiceState.WakeListening
        } else {
            setMicOwner(MicOwner.NONE)
        }
    }

    /** Re-open the mic after a half-duplex pause (TTS playback), for whoever should own it now. */
    private fun resumeMicIfNeeded() {
        if (audio.isRecording.value) return
        if (asr != null) setMicOwner(MicOwner.ASR)
        else if (wakeSessionActive && !wakeArmed) setMicOwner(MicOwner.KWS)
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
            setMicOwner(MicOwner.NONE)
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
                _voiceState.value = VoiceState.WakeListening
                backToWakeListening()
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
        if (text.isBlank()) {
            _voiceState.value = VoiceState.WakeListening
        } else {
            ws.sendLog("info", "VoiceService", "wake command -> gateway: $text")
            _voiceState.value = VoiceState.Sent(text)
            _lastReply.value = ""
            ws.sendAsrText(text)
        }
        backToWakeListening()
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
