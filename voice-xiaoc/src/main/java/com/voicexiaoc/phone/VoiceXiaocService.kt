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
        // 说完话到发出去之间的静音等待。这是纯等待 —— 他已经说完了，我们还在等
        // 他会不会继续说。2500ms 占了整条链路延迟的三分之一（实测一轮 7.2s 里
        // 有 2.5s 是它）。砍到 1200ms：中文一句话的自然停顿约 300-600ms，1.2s
        // 足够区分"说完了"和"喘口气"，再长就是白等。
        // 代价：说话中间停顿超过 1.2 秒会被切成两句。真被切了就调回去。
        private const val ARM_SILENCE_MS = 1200L       // armed, said something, now paused — submit

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

    /** 本轮播报里出没出现过唤醒词（决定播报期间开不开麦，见 ttsAudio 收集处）。 */
    @Volatile private var roundHasWakeWord = false

    lateinit var config: ConfigStore; private set
    lateinit var ws: WsClient; private set
    lateinit var tts: TtsPlayer; private set
    lateinit var ota: OtaUpdater; private set
    lateinit var versionChecker: VersionChecker; private set
    lateinit var audio: AudioCapture; private set
    lateinit var kws: KwsDetector; private set
    lateinit var locator: LocationReporter; private set

    // Last reply text surfaced to the UI.
    private val _lastReply = MutableStateFlow("")
    val lastReply: StateFlow<String> = _lastReply.asStateFlow()

    // Push-to-talk voice pipeline state (P2a).
    private val _voiceState = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    private var asr: TencentAsrClient? = null

    /**
     * 唤醒/录音的全部时序与状态都归 [WakeMachine]（WakeMachine.kt，带 12 个单测）。
     * 本 Service 只负责把状态机的意图落到真实设备上：开关麦克风、开关云端 ASR、
     * 掐播报、把收齐的命令发出去。状态机不碰 Android API，Service 不做时序判断。
     */
    private lateinit var machine: WakeMachine
    private var micOwner = WakeMachine.MicOwner.NONE

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "onCreate")

        config = ConfigStore(this)
        tts = TtsPlayer(this)
        audio = AudioCapture(this)
        kws = KwsDetector(this)
        locator = LocationReporter(this) { loc ->
            ws.sendLocation(
                lat = loc.latitude, lon = loc.longitude,
                accuracy = if (loc.hasAccuracy()) loc.accuracy else null,
                speed = if (loc.hasSpeed()) loc.speed else null,
                bearing = if (loc.hasBearing()) loc.bearing else null,
                altitude = if (loc.hasAltitude()) loc.altitude else null,
                provider = loc.provider, fixedAt = loc.time,
            )
        }
        machine = WakeMachine(
            scope = scope,
            armTimeoutMs = WAKE_ARM_TIMEOUT_MS,
            silenceSubmitMs = ARM_SILENCE_MS,
            onMic = ::applyMicOwner,
            onOpenAsr = ::openAsr,
            onCloseAsr = ::closeAsr,
            onCommand = ::sendCommand,
            onStopSpeaking = { tts.stop() },
            onState = ::onMachineState,
            onDiag = { ws.sendLog("info", "WakeMachine", it) },
        )
        ws = WsClient(scope).apply {
            host = config.host
            port = config.port
            token = config.token
            deviceId = "phone-${Build.MODEL}".replace(" ", "_")
        }
        audio.onLog = { level, msg -> ws.sendLog(level, "AudioCapture", msg) }
        locator.onLog = { level, msg -> ws.sendLog(level, "LocationReporter", msg) }
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
                // 只更新 UI。跟进窗口由状态机在播报结束(onSpoken)时开，Service 不
                // 自己起定时器抢麦——之前正是这里的 800ms 定时器在播报刚开始就重开
                // 麦克风，造成 ASR 听见小C自己的声音的自激循环。
            }
        }
        scope.launch {
            ws.ttsAudio.collect { a ->
                // 回复文本里若正好含唤醒词(如"祝你健康顺利")，播报期间闭麦，
                // 免得小C把自己打断。
                //
                // 分句流式下这个判断要对**整轮**生效：唤醒词可能出现在第三段，
                // 而第一段到达时就得决定开不开麦。所以任何一段撞上唤醒词，就把
                // 本轮剩下的时间都闭麦 —— 宁可这一轮打断不了，也不能自己把自己
                // 打断（那会卡在"说一句就中断"的死循环里）。
                if (a.text.contains(WAKE_WORD)) roundHasWakeWord = true
                machine.onSpeaking(allowBargeIn = !roundHasWakeWord)
                // Half-duplex: pause the mic for the duration of playback. AEC
                // alone didn't reliably stop the phone hearing its own TTS
                // through the speaker and re-transcribing it as user speech
                // (self-talk echo loop) — the surest fix is to just not listen
                // while we're talking.
                tts.enqueue(a.base64, a.format, isFinal = a.isFinal, onDone = {
                    roundHasWakeWord = false
                    scope.launch {
                        kotlinx.coroutines.delay(300) // 等房间混响拖尾散掉
                        machine.onSpoken()
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
        locator.start()
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
        machine.stop()
        locator.stop()
        audio.cleanup()
        asr?.cancel(); asr = null
        kws.release()
        ws.disconnect()
        tts.cleanup()
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        instance = null
    }

    /** 用户在界面上授予定位权限后调用，无需重启服务即可开始上报。 */
    fun onLocationPermissionGranted() = locator.start()

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
        when (machine.state) {
            WakeMachine.State.IDLE -> return
            WakeMachine.State.ARMED, WakeMachine.State.FOLLOW_UP -> machine.submit()
            else -> {
                ws.sendLog("info", "VoiceService", "manual tap = force wake")
                machine.onWake()
            }
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
    private fun startWakeSession() = machine.start()

    // ══ 设备执行层 ══════════════════════════════════════════════════
    // 以下都是 WakeMachine 的"手脚"：状态机决定何时做什么，这里只负责怎么做。
    // 任何时序判断都不该出现在这一层——那是状态机的职责，且有单测覆盖。

    /** 切换麦克风归属。先停后开，[micOwner] 因此永远描述真实情况。 */
    private fun applyMicOwner(owner: WakeMachine.MicOwner) {
        if (micOwner == owner && audio.isRecording.value) return
        audio.stop()
        micOwner = owner
        when (owner) {
            WakeMachine.MicOwner.NONE -> Unit
            WakeMachine.MicOwner.KWS -> audio.startPcm(scope) { pcm ->
                kws.feed(pcm)?.let { hit ->
                    ws.sendLog("info", "KwsDetector", "local wake hit: $hit")
                    scope.launch { machine.onWake() }
                }
            }
            WakeMachine.MicOwner.ASR -> {
                val client = asr
                if (client == null) micOwner = WakeMachine.MicOwner.NONE
                else audio.startPcm(scope) { pcm -> client.sendPcm(pcm) }
            }
        }
    }

    /** 开一路腾讯云 ASR 专门录这句命令（唤醒已由本地 KWS 确认）。 */
    private fun openAsr() {
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
                ws.sendLog("info", "TencentAsr", "command capture ready")
                if (asr === client) applyMicOwner(WakeMachine.MicOwner.ASR)
            }
            override fun onPartial(text: String) {
                // 记下来：之前 partial 完全不上报，导致"ASR 到底听没听见"是盲区，
                // 只能靠状态机超时了几秒去反推。内容会被网关脱敏成字数。
                ws.sendLog("debug", "TencentAsr", "partial: $text")
                _voiceState.value = VoiceState.Recognizing(text)
                machine.onHeard(text, isFinal = false)
            }
            override fun onFinal(text: String) {
                ws.sendLog("info", "TencentAsr", "command final: $text")
                machine.onHeard(text, isFinal = true)
            }
            override fun onCompleted() {
                ws.sendLog("info", "TencentAsr", "stream completed (server final=1)")
                if (asr === client) { asr = null; machine.submit() }
            }
            override fun onError(msg: String) {
                ws.sendLog("error", "TencentAsr", "command capture error: $msg")
                if (asr !== client) return
                asr = null
                _voiceState.value = VoiceState.Error(msg)
                scope.launch { kotlinx.coroutines.delay(1500); machine.onError() }
            }
        })
    }

    /**
     * 优雅收尾：先发 end 标记(finish)让腾讯把攒着的最后一句吐出来，再延时硬关。
     * 之前直接 cancel() 会把待定稿的句子连同连接一起丢掉 —— 腾讯的 final
     * (slice_type=2) 本来就依赖它自己的断句判定，再被我们提前掐断，就出现了
     * "听见了但一个字都没拿到"。
     */
    private fun closeAsr() {
        val client = asr ?: return
        asr = null
        client.finish()
        scope.launch { kotlinx.coroutines.delay(1500); client.cancel() }
    }

    /** 一句完整命令收齐 → 发给网关。 */
    private fun sendCommand(text: String) {
        ws.sendLog("info", "VoiceService", "wake command -> gateway: $text")
        _lastReply.value = ""
        val delivered = ws.sendAsrText(text)
        if (delivered) {
            _voiceState.value = VoiceState.Sent(text)
        } else {
            // 别再让统帅对着黑洞说话。2026-08-10 就是这样：唤醒成功、识别成功，
            // 但连接是半开的，这句直接进了黑洞，屏幕上没提示、也没声音，
            // 表现就是"我说了它没反应"。现在明确告知，并已入发件箱等重连补发。
            _voiceState.value = VoiceState.Error("网络断开，这句已暂存，连上后自动补发")
            beepFailure()
        }
    }

    /** 命令没能立刻送达时的提示音。开车时看不了屏幕，得让耳朵知道。 */
    private fun beepFailure() {
        try {
            val tone = android.media.ToneGenerator(
                android.media.AudioManager.STREAM_MUSIC, 60)
            tone.startTone(android.media.ToneGenerator.TONE_PROP_NACK, 300)
            scope.launch { kotlinx.coroutines.delay(600); tone.release() }
        } catch (e: Exception) {
            ws.sendLog("warn", "VoiceService", "beepFailure 失败: ${e.message}")
        }
    }

    /** 状态机状态 → UI 状态 + 远程日志（维度3：状态自报）。 */
    private fun onMachineState(state: WakeMachine.State) {
        ws.sendLog("info", "WakeMachine", "state -> $state")
        when (state) {
            WakeMachine.State.IDLE -> _voiceState.value = VoiceState.Idle
            WakeMachine.State.WAKE_LISTENING -> _voiceState.value = VoiceState.WakeListening
            WakeMachine.State.ARMED, WakeMachine.State.FOLLOW_UP ->
                _voiceState.value = VoiceState.Listening
            WakeMachine.State.SPEAKING -> Unit   // 播报中，UI 已由 Reply 状态占用
        }
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
