package com.superbrain.glasses

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

/**
 * Foreground Service that owns all SuperBrain resources.
 * Survives Activity destruction, screen-off, and persists across reboots (via BootReceiver).
 */
class SuperBrainService : Service() {

    companion object {
        private const val TAG = "SuperBrainService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "superbrain_service"


        @Volatile
        var instance: SuperBrainService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, SuperBrainService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _hudState = MutableStateFlow(HudState(statusText = "SuperBrain"))
    val hudState: StateFlow<HudState> = _hudState.asStateFlow()

    lateinit var wsClient: WsClient; private set
    lateinit var cameraCapture: CameraCapture; private set
    lateinit var audioCapture: AudioCapture; private set
    lateinit var ttsPlayer: TtsPlayer; private set
    lateinit var otaUpdater: OtaUpdater; private set
    lateinit var wifiController: WifiController; private set
    lateinit var videoRecorder: VideoRecorder; private set
    lateinit var configStore: ConfigStore; private set
    private lateinit var adbController: AdbController

    // Pending photo for 小C (captured on wake word, sent with ASR final)
    private var pendingPhoto: String? = null

    // Observer mode (旁听)
    private var observerMode = false

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // TTS 音频队列 — 串行播放多段 mp3，防止重叠
    private val audioQueue: java.util.concurrent.LinkedBlockingQueue<ByteArray> = java.util.concurrent.LinkedBlockingQueue()
    private var audioIsPlaying = false
    private var audioCurrentPlayer: android.media.MediaPlayer? = null
    private var audioWasRecording = false

    // Binder for Activity binding
    inner class LocalBinder : Binder() {
        val service: SuperBrainService get() = this@SuperBrainService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "Service created")

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))

        // Acquire locks
        acquireWifiLock()
        acquireWakeLock()

        // Initialize all components
        configStore = ConfigStore(this)
        wsClient = WsClient(scope)
        cameraCapture = CameraCapture(this)
        audioCapture = AudioCapture(this)
        ttsPlayer = TtsPlayer(this)
        otaUpdater = OtaUpdater(this, scope)
        wifiController = WifiController(this)
        videoRecorder = VideoRecorder(this)

        // Register ADB receiver on Service (survives Activity death)
        registerAdbReceiver()

        // Collect WebSocket events
        collectWsEvents()

        // Register network callback for auto-reconnect
        registerNetworkCallback()

        // WiFi watchdog: re-enable WiFi if system turns it off
        startWifiWatchdog()

        // Auto-connect if configured
        if (configStore.isConfigured && configStore.autoConnect) {
            Log.i(TAG, "Auto-connecting with saved config: ${configStore.host}:${configStore.port}")
            wsClient.host = configStore.host
            wsClient.port = configStore.port
            wsClient.token = configStore.token
            wsClient.connect()
            addSystemMessage("Auto-connecting to ${configStore.host}:${configStore.port}...")
        }

        addSystemMessage("Ready. Use ADB broadcast to configure & connect.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY // System will restart this service if killed
    }

    // ── ADB command handlers ──

    fun handleConfig(host: String, port: Int, token: String) {
        configStore.save(host, port, token)
        wsClient.host = host
        wsClient.port = port
        wsClient.token = token
        Log.i(TAG, "Configured: $host:$port")
        addSystemMessage("Config saved: $host:$port")
    }

    fun handleConnect() {
        wsClient.connect()
    }

    fun handleDisconnect() {
        wsClient.disconnect()
    }

    fun handleSend(text: String) {
        _hudState.update { state ->
            state.copy(messages = state.messages + HudMessage("user", text))
        }
        wsClient.sendChat(text)
    }

    fun handlePhoto() {
        try {
            wakeScreen()
            addSystemMessage("Capturing photo...")
            cameraCapture.capture { file ->
                scope.launch(Dispatchers.Main) {
                    if (file != null) {
                        addSystemMessage("Photo captured: ${file.length() / 1024}KB")
                        // ADB PHOTO 路径不用 base64 attachment 了（4MB JPEG + 6MB base64 = OOM）
                        // 如要发给小C 让 take_photo command 走 OSS 路径
                    } else {
                        addSystemMessage("Photo capture failed")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Photo error: ${e.message}", e)
            addSystemMessage("Photo error: ${e.message}")
        }
    }

    fun handleListenStart(mode: String = "conversation") {
        _hudState.update { it.copy(isListening = true, asrText = "", asrIsFinal = false) }
        wsClient.resetAudioSeq()
        wsClient.sendAudioStart()
        audioCapture.start(scope, mode) { base64Pcm ->
            val seq = wsClient.nextAudioSeq()
            wsClient.sendAudioChunk(base64Pcm, seq)
        }
        addSystemMessage("Listening...")
    }

    fun handleListenStop() {
        audioCapture.stop()
        wsClient.sendAudioStop()
        _hudState.update { it.copy(isListening = false) }
        addSystemMessage("Stopped listening")
    }

    fun handleDisplay(text: String) {
        addSystemMessage(text)
    }

    fun handleOta(url: String) {
        addSystemMessage("OTA: starting download...")
        otaUpdater.startUpdate(url) { progress ->
            addSystemMessage("OTA: $progress")
        }
    }

    fun handleWifi(ssid: String, password: String) {
        addSystemMessage("WiFi: connecting to $ssid...")
        wifiController.connectToWifi(ssid, password) { success, message ->
            scope.launch(Dispatchers.Main) {
                addSystemMessage("WiFi: $message")
                if (success) {
                    Log.i(TAG, "WiFi connected, reconnecting WebSocket...")
                    delay(2000)
                    if (wsClient.host.isNotBlank()) {
                        wsClient.connect()
                    }
                }
            }
        }
    }

    fun handleWifiStatus() {
        val status = wifiController.getWifiStatus()
        Log.i(TAG, "WiFi: $status")
        addSystemMessage("WiFi: $status")
    }

    fun handleStatus() {
        val status = buildString {
            appendLine("=== SuperBrain Status ===")
            appendLine("WS: ${wsClient.getStatus()}")
            appendLine("Audio: recording=${audioCapture.isRecording.value}")
            appendLine("Camera: capturing=${cameraCapture.isCapturing}, recording=${videoRecorder.isRecording}")
            appendLine("TTS: enabled=${ttsPlayer.enabled}")
            appendLine("OTA: updating=${otaUpdater.isUpdating}")
            appendLine("WiFi: ${wifiController.getWifiStatus()}")
            appendLine("Config: $configStore")
            appendLine("Messages: ${_hudState.value.messages.size}")
        }
        Log.i(TAG, status)
        addSystemMessage(status.trim())
    }

    /**
     * Called when ASR final result is received in wake-word-triggered mode.
     * Keep recording so user can continue the conversation.
     * Recording only stops when a sleep command arrives from VPS.
     */
    fun onAsrComplete() {
        Log.i(TAG, "ASR complete — continuing to listen for next utterance")
    }

    /**
     * Attempt NTP time sync. Retries up to [maxRetries] times waiting for WiFi.
     * Must be called from a background (IO) coroutine.
     */
    private suspend fun syncTimeWithRetry(maxRetries: Int = 5): Boolean {
        repeat(maxRetries) { attempt ->
            val ok = NtpSync.syncTime(applicationContext)
            if (ok) {
                Log.i(TAG, "NTP sync succeeded on attempt ${attempt + 1}")
                withContext(Dispatchers.Main) {
                    addSystemMessage("Time synced via NTP")
                }
                return true
            }
            Log.w(TAG, "NTP sync attempt ${attempt + 1} failed, retrying in 3s...")
            delay(3000)
        }
        Log.e(TAG, "NTP sync failed after $maxRetries attempts")
        withContext(Dispatchers.Main) {
            addSystemMessage("NTP sync failed")
        }
        return false
    }

    // ── Internal ──

    private fun registerAdbReceiver() {
        adbController = AdbController()
        val filter = IntentFilter().apply {
            addAction("com.superbrain.glasses.CONFIG")
            addAction("com.superbrain.glasses.CONNECT")
            addAction("com.superbrain.glasses.DISCONNECT")
            addAction("com.superbrain.glasses.SEND")
            addAction("com.superbrain.glasses.PHOTO")
            addAction("com.superbrain.glasses.LISTEN_START")
            addAction("com.superbrain.glasses.LISTEN_STOP")
            addAction("com.superbrain.glasses.DISPLAY")
            addAction("com.superbrain.glasses.STATUS")
            addAction("com.superbrain.glasses.OTA")
            addAction("com.superbrain.glasses.WIFI")
            addAction("com.superbrain.glasses.WIFI_STATUS")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(adbController, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(adbController, filter)
        }
        Log.i(TAG, "ADB receiver registered on Service")
    }

    private fun collectWsEvents() {
        // Chat events (streaming)
        scope.launch {
            wsClient.chatEvents.collect { event ->
                when (event) {
                    is WsClient.ChatEvent.Delta -> {
                        _hudState.update { state ->
                            val messages = state.messages.toMutableList()
                            val lastIdx = messages.indexOfLast { it.role == "assistant" && it.isStreaming }
                            if (lastIdx >= 0) {
                                messages[lastIdx] = messages[lastIdx].copy(content = event.text)
                            } else {
                                messages.add(HudMessage("assistant", event.text, isStreaming = true))
                            }
                            state.copy(messages = messages, isStreaming = true, streamingText = event.text)
                        }
                    }
                    is WsClient.ChatEvent.Final -> {
                        _hudState.update { state ->
                            val messages = state.messages.toMutableList()
                            val lastIdx = messages.indexOfLast { it.role == "assistant" && it.isStreaming }
                            if (lastIdx >= 0) {
                                messages[lastIdx] = messages[lastIdx].copy(content = event.text, isStreaming = false)
                            } else {
                                messages.add(HudMessage("assistant", event.text))
                            }
                            state.copy(messages = messages, isStreaming = false, streamingText = "")
                        }
                        ttsPlayer.speak(event.text)
                    }
                    is WsClient.ChatEvent.Error -> {
                        _hudState.update { state ->
                            val messages = state.messages.toMutableList()
                            val lastIdx = messages.indexOfLast { it.isStreaming }
                            if (lastIdx >= 0) {
                                messages[lastIdx] = messages[lastIdx].copy(isStreaming = false)
                            }
                            messages.add(HudMessage("system", "Error: ${event.message}"))
                            state.copy(messages = messages, isStreaming = false)
                        }
                    }
                }
            }
        }

        // Status messages
        scope.launch {
            wsClient.statusMessages.collect { msg ->
                addSystemMessage(msg)
                updateNotification(msg)
            }
        }

        // Connection state
        var everConnected = false
        scope.launch {
            wsClient.connected.collect { connected ->
                _hudState.update { it.copy(isConnected = connected) }
                updateNotification(if (connected) "Connected" else "Disconnected")
                if (!connected && everConnected) {
                    playLocalSound(R.raw.disconnected)
                }
                if (connected) everConnected = true
            }
        }

        // OTA events
        scope.launch {
            wsClient.otaEvents.collect { event ->
                Log.i(TAG, "OTA event received: v${event.version}")
                addSystemMessage("OTA update v${event.version}")
                handleOta(event.url)
            }
        }

        // ASR events
        scope.launch {
            wsClient.asrEvents.collect { event ->
                _hudState.update { state ->
                    if (event.isFinal) {
                        val messages = state.messages + HudMessage("user", event.text)
                        state.copy(messages = messages, asrText = "", asrIsFinal = true)
                    } else {
                        state.copy(asrText = event.text, asrIsFinal = false)
                    }
                }
                // VPS ASR callback already routes to LLM — don't send chat.send again
                // (was causing duplicate responses)
            }
        }

        // WiFi events
        scope.launch {
            wsClient.wifiEvents.collect { event ->
                Log.i(TAG, "WiFi event received: ${event.ssid}")
                handleWifi(event.ssid, event.password)
            }
        }

        // Command events
        scope.launch {
            wsClient.commandEvents.collect { event ->
                when (event.action) {
                    "sleep" -> {
                        Log.i(TAG, "Sleep command")
                        observerMode = false
                        playLocalSoundAndWait(R.raw.bye_bye)
                        handleListenStop()
                        _hudState.update { it.copy(observerMode = false) }
                    }
                    "observer_start" -> {
                        Log.i(TAG, "Observer mode ON")
                        observerMode = true
                        _hudState.update { it.copy(observerMode = true) }
                        handleListenStart("ambient")
                    }
                    "observer_stop" -> {
                        Log.i(TAG, "Observer mode OFF")
                        observerMode = false
                        _hudState.update { it.copy(observerMode = false) }
                        handleListenStop()
                    }
                    "take_photo" -> {
                        Log.i(TAG, "Photo requested by server")
                        val payload = event.payload
                        val putUrl = payload?.get("put_url")?.asString
                        val ossKey = payload?.get("key")?.asString
                        if (videoRecorder.isRecording) {
                            Log.w(TAG, "Cannot take photo while recording video")
                            addSystemMessage("录像中，无法拍照")
                            wsClient.sendPhotoResult(null)
                        } else {
                            try {
                                wakeScreen()
                                cameraCapture.capture { file ->
                                    scope.launch(Dispatchers.IO) {
                                        if (file == null) {
                                            wsClient.sendPhotoResult(null)
                                            Log.w(TAG, "Photo capture returned null")
                                            return@launch
                                        }
                                        if (putUrl.isNullOrBlank() || ossKey.isNullOrBlank()) {
                                            // 无 presigned URL：live preview 等老路径，走 base64（小图）
                                            Log.i(TAG, "No put_url → fallback base64 path")
                                            // 这条路径基本不用（大图会 OOM），保留给 live preview 的小图场景
                                            wsClient.sendPhotoResult(null)
                                            return@launch
                                        }
                                        // 轻量上传：OkHttp 流式 PUT 到 presigned URL（零 base64，零 SDK）
                                        val ok = uploadFileToPresignedUrl(file, putUrl)
                                        if (ok) {
                                            wsClient.sendPhotoUploaded(
                                                key = ossKey,
                                                url = null,
                                                size = file.length(),
                                                format = "jpeg"
                                            )
                                            Log.i(TAG, "Photo uploaded: $ossKey (${file.length()/1024}KB)")
                                        } else {
                                            Log.e(TAG, "Photo upload PUT failed")
                                            wsClient.sendPhotoResult(null)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Take photo error: ${e.message}")
                                wsClient.sendPhotoResult(null)
                            }
                        }
                    }
                    "listen_start" -> {
                        Log.i(TAG, "Listen start command")
                        handleListenStart()
                    }
                    "listen_stop" -> {
                        Log.i(TAG, "Listen stop command")
                        handleListenStop()
                    }
                    "record_start" -> {
                        Log.i(TAG, "Record start command")
                        if (cameraCapture.isCapturing) {
                            addSystemMessage("拍照中，请稍后再录像")
                        } else {
                            wakeScreen()
                            addSystemMessage("录像中...")
                            videoRecorder.start { success, message ->
                                if (!success) addSystemMessage("录像失败: $message")
                            }
                        }
                    }
                    "record_stop" -> {
                        Log.i(TAG, "Record stop command")
                        videoRecorder.stop { success, filePath ->
                            if (success) {
                                addSystemMessage("录像已保存")
                                Log.i(TAG, "Video saved: $filePath")
                            } else {
                                addSystemMessage("未在录像")
                            }
                        }
                    }
                    "play_audio" -> {
                        val payload = event.payload
                        val data = payload?.get("data")?.asString ?: ""
                        if (data.isNotBlank()) {
                            Log.i(TAG, "Queuing audio: ${data.length} chars base64")
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val bytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
                                    withContext(Dispatchers.Main) {
                                        audioQueue.offer(bytes)
                                        if (!audioIsPlaying) {
                                            // 第一段：记录 ASR 状态并暂停
                                            audioWasRecording = audioCapture.isRecording.value
                                            if (audioWasRecording) audioCapture.stop()
                                            playNextFromQueue()
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Play audio decode error: ${e.message}")
                                }
                            }
                        }
                    }
                    "sync_time" -> {
                        // VPS can push its own timestamp as a fallback when NTP is unreachable
                        val payload = event.payload
                        val epochMs = payload?.get("epochMs")?.asLong ?: 0L
                        scope.launch(Dispatchers.IO) {
                            val ok = if (epochMs > 0) {
                                NtpSync.setTimeFromServer(applicationContext, epochMs)
                            } else {
                                NtpSync.syncTime(applicationContext)
                            }
                            Log.i(TAG, "sync_time command: ok=$ok epochMs=$epochMs")
                            withContext(Dispatchers.Main) {
                                addSystemMessage("Time sync: ${if (ok) "OK" else "failed"}")
                            }
                        }
                    }
                    "shell" -> {
                        val payload = event.payload
                        val cmd = payload?.get("cmd")?.asString ?: return@collect
                        val requestId = payload.get("requestId")?.asString ?: ""
                        Log.i(TAG, "Shell command: requestId=$requestId cmd=${cmd.take(100)}")
                        scope.launch(Dispatchers.IO) {
                            try {
                                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                                val output = process.inputStream.bufferedReader().readText()
                                val error = process.errorStream.bufferedReader().readText()
                                val exitCode = process.waitFor()
                                val fullOutput = if (error.isNotEmpty()) output + "\nSTDERR: " + error else output
                                wsClient.sendShellResult(requestId, cmd, fullOutput.take(65000), exitCode)
                            } catch (e: Exception) {
                                Log.e(TAG, "Shell exec error: ${e.message}")
                                wsClient.sendShellResult(requestId, cmd, "ERROR: ${e.message}", -1)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Network available")
                // Init models the FIRST time network is available (NTP + Xunfei online auth need WiFi)
                scope.launch(Dispatchers.IO) { syncTimeWithRetry() }
                // Auto-reconnect if configured and disconnected
                if (!wsClient.connected.value && configStore.autoConnect && configStore.isConfigured) {
                    scope.launch {
                        delay(1000) // Brief delay for network stabilization
                        if (!wsClient.connected.value) {
                            Log.i(TAG, "Network recovered, reconnecting WebSocket")
                            wsClient.host = configStore.host
                            wsClient.port = configStore.port
                            wsClient.token = configStore.token
                            wsClient.connect()
                        }
                    }
                }
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "Network lost")
                addSystemMessage("Network lost")
            }
        }
        networkCallback = callback
        cm.registerDefaultNetworkCallback(callback)
    }

    @Suppress("DEPRECATION")
    private fun startWifiWatchdog() {
        scope.launch(Dispatchers.IO) {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            // Check immediately on start (no delay on first iteration)
            while (true) {
                if (!wm.isWifiEnabled) {
                    Log.w(TAG, "WiFi watchdog: WiFi is OFF, re-enabling...")
                    // Settings.Global works with WRITE_SECURE_SETTINGS (granted via ADB)
                    // WifiManager.setWifiEnabled is broken on Android 10+ for non-system apps
                    try {
                        Settings.Global.putInt(contentResolver, Settings.Global.WIFI_ON, 1)
                        Log.i(TAG, "WiFi watchdog: enabled via Settings.Global")
                    } catch (e: Exception) {
                        Log.w(TAG, "WiFi watchdog: Settings.Global failed, trying WifiManager", e)
                        wm.isWifiEnabled = true
                    }
                }
                delay(30_000) // Check every 30 seconds
            }
        }
    }

    private fun acquireWifiLock() {
        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SuperBrain:WS")
            wifiLock?.acquire()
            Log.i(TAG, "WiFi lock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WiFi lock: ${e.message}")
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SuperBrain:Service")
            wakeLock?.acquire()
            Log.i(TAG, "Wake lock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock: ${e.message}")
        }
    }

    /** TTS 队列化串行播放：从 audioQueue 取下一段，播完后递归取下一段。 */
    private fun playNextFromQueue() {
        val bytes = audioQueue.poll()
        if (bytes == null) {
            audioIsPlaying = false
            if (audioWasRecording) {
                handleListenStart()
                Log.i(TAG, "ASR resumed — TTS queue empty")
            }
            return
        }
        audioIsPlaying = true

        // 锁 STREAM_VOICE_CALL 和 STREAM_MUSIC 到 100%（VOICE_CALL 不受 BT AVRCP 控制）
        val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        for (stream in listOf(android.media.AudioManager.STREAM_VOICE_CALL, android.media.AudioManager.STREAM_MUSIC)) {
            val max = am.getStreamMaxVolume(stream)
            if (am.getStreamVolume(stream) != max) am.setStreamVolume(stream, max, 0)
        }

        val tempFile = java.io.File.createTempFile("tts_", ".mp3", cacheDir)
        tempFile.writeBytes(bytes)

        val mp = android.media.MediaPlayer()
        mp.setAudioAttributes(
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        mp.setDataSource(tempFile.absolutePath)
        mp.setOnCompletionListener {
            it.release()
            tempFile.delete()
            audioCurrentPlayer = null
            Log.i(TAG, "TTS segment done, queue=${audioQueue.size} remaining")
            playNextFromQueue()  // 播下一段
        }
        mp.setOnErrorListener { p, what, extra ->
            Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
            p.release()
            tempFile.delete()
            audioCurrentPlayer = null
            playNextFromQueue()  // 出错也继续队列
            true
        }
        mp.setOnPreparedListener { p ->
            p.setVolume(1.0f, 1.0f)
            p.start()
            Log.i(TAG, "TTS segment started, queue=${audioQueue.size} remaining")
        }
        mp.prepareAsync()
        audioCurrentPlayer = mp
    }

        /** 流式 PUT 文件到 presigned URL。无内存压力（OkHttp 从流读文件）。 */
    private fun buildTrustAllHttpClient(): okhttp3.OkHttpClient {
        // Glasses system clock is often wrong (NTP fails), causing TLS cert
        // validation to fail. Trust all certs for OSS uploads on this device.
        val trustAll = arrayOf<javax.net.ssl.TrustManager>(
            object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            }
        )
        val sslCtx = javax.net.ssl.SSLContext.getInstance("TLS")
        sslCtx.init(null, trustAll, java.security.SecureRandom())
        return okhttp3.OkHttpClient.Builder()
            .sslSocketFactory(sslCtx.socketFactory, trustAll[0] as javax.net.ssl.X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private fun uploadFileToPresignedUrl(file: java.io.File, putUrl: String): Boolean {
        return try {
            val client = buildTrustAllHttpClient()
            val mt = "application/octet-stream".toMediaTypeOrNull()
            val body = file.asRequestBody(mt)
            val req = okhttp3.Request.Builder().url(putUrl).put(body).build()
            val t0 = System.currentTimeMillis()
            client.newCall(req).execute().use { resp ->
                val ms = System.currentTimeMillis() - t0
                Log.i(TAG, "OSS PUT ${file.length()/1024}KB → HTTP ${resp.code} in ${ms}ms")
                resp.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "uploadFileToPresignedUrl failed", e)
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) {
            val wl = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
                "superbrain:camera"
            )
            wl.acquire(3000)
        }
    }

    fun addSystemMessage(text: String) {
        _hudState.update { state ->
            val messages = state.messages.toMutableList()
            if (messages.size > 100) {
                messages.removeAt(0)
            }
            messages.add(HudMessage("system", text))
            state.copy(messages = messages, statusText = text.take(40))
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SuperBrain Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "SuperBrain glasses background service"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("SuperBrain")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Exception) {}
    }


    private fun playLocalSound(resId: Int) {
        try {
            val mp = android.media.MediaPlayer.create(this, resId) ?: return
            mp.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setOnCompletionListener { it.release() }
            mp.start()
        } catch (e: Exception) {
            Log.w(TAG, "playLocalSound failed")
        }
    }

    private suspend fun playLocalSoundAndWait(resId: Int) {
        val deferred = kotlinx.coroutines.CompletableDeferred<Unit>()
        try {
            val mp = android.media.MediaPlayer.create(this, resId)
            if (mp == null) { deferred.complete(Unit); return }
            mp.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setOnCompletionListener { it.release(); deferred.complete(Unit) }
            mp.setOnErrorListener { p, _, _ -> p.release(); deferred.complete(Unit); true }
            mp.start()
        } catch (e: Exception) {
            Log.w(TAG, "playLocalSoundAndWait failed")
            deferred.complete(Unit)
        }
        deferred.await()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "Service destroyed")

        // Unregister receivers
        try { unregisterReceiver(adbController) } catch (_: Exception) {}

        // Unregister network callback
        networkCallback?.let {
            try {
                val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }

        // Release locks
        wifiLock?.let { if (it.isHeld) it.release() }
        wakeLock?.let { if (it.isHeld) it.release() }

        // Cleanup all components
        wsClient.disconnect()
        videoRecorder.cleanup()
        audioCapture.cleanup()
        cameraCapture.cleanup()
        ttsPlayer.cleanup()
        otaUpdater.cleanup()
        wifiController.cleanup()
        // 清空 TTS 音频队列
        audioQueue.clear()
        audioCurrentPlayer?.release()
        audioCurrentPlayer = null
        audioIsPlaying = false

        scope.cancel()
    }
}
