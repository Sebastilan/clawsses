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
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    lateinit var configStore: ConfigStore; private set
    private lateinit var adbController: AdbController

    // Wake word + Speaker verification
    lateinit var wakeWordEngine: WakeWordEngine; private set
    lateinit var speakerVerifier: SpeakerVerifier; private set
    private var wakeWordEnabled = false
    private var modelsReady = false

    // Pending photo for 小C (captured on wake word, sent with ASR final)
    private var pendingPhoto: String? = null

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

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

        // Initialize wake word + speaker verification
        wakeWordEngine = WakeWordEngine(this)
        speakerVerifier = SpeakerVerifier(this)
        initModels()

        // Register ADB receiver on Service (survives Activity death)
        registerAdbReceiver()

        // Collect WebSocket events
        collectWsEvents()

        // Register network callback for auto-reconnect
        registerNetworkCallback()

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
            cameraCapture.capture { base64 ->
                scope.launch(Dispatchers.Main) {
                    if (base64 != null) {
                        addSystemMessage("Photo captured, sending to AI...")
                        val attachments = listOf(
                            mapOf("mimeType" to "image/jpeg", "content" to base64)
                        )
                        _hudState.update { state ->
                            state.copy(messages = state.messages + HudMessage("user", "[Photo sent]"))
                        }
                        wsClient.sendChat("Describe what you see in this image.", attachments)
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

    fun handleListenStart() {
        _hudState.update { it.copy(isListening = true, asrText = "", asrIsFinal = false) }
        wsClient.resetAudioSeq()
        wsClient.sendAudioStart()
        audioCapture.start(scope) { base64Pcm ->
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
            appendLine("Camera: capturing=${cameraCapture.isCapturing}")
            appendLine("TTS: enabled=${ttsPlayer.enabled}")
            appendLine("OTA: updating=${otaUpdater.isUpdating}")
            appendLine("WiFi: ${wifiController.getWifiStatus()}")
            appendLine("WakeWord: enabled=$wakeWordEnabled, running=${wakeWordEngine.isRunning.value}, models=$modelsReady")
            appendLine("Speaker: enrolled=${speakerVerifier.isEnrolled}")
            appendLine("Config: $configStore")
            appendLine("Messages: ${_hudState.value.messages.size}")
        }
        Log.i(TAG, status)
        addSystemMessage(status.trim())
    }

    // ── Wake word / Speaker verification handlers ──

    fun handleWakeEnable() {
        if (!modelsReady) {
            addSystemMessage("Models not loaded. Push models to device first.")
            return
        }
        if (wakeWordEnabled) return
        wakeWordEnabled = true
        _hudState.update { it.copy(wakeWordActive = true) }
        wakeWordEngine.start(scope) { keyword, audioSamples ->
            onWakeWordDetected(keyword, audioSamples)
        }
        addSystemMessage("Wake word enabled: say '小C'")
    }

    fun handleWakeDisable() {
        wakeWordEnabled = false
        wakeWordEngine.stop()
        _hudState.update { it.copy(wakeWordActive = false) }
        addSystemMessage("Wake word disabled")
    }

    fun handleEnrollStart() {
        if (!modelsReady) {
            addSystemMessage("Models not loaded")
            return
        }
        speakerVerifier.startEnrollment()
        _hudState.update { it.copy(
            enrolling = true,
            enrollProgress = 0,
            enrollNeeded = speakerVerifier.enrollNeeded
        ) }
        addSystemMessage("Say '小C' ${speakerVerifier.enrollNeeded} times to enroll")
        // Temporarily stop wake word to use mic for enrollment
        val wasEnabled = wakeWordEnabled
        if (wasEnabled) wakeWordEngine.stop()
        // Start recording for enrollment
        wakeWordEngine.start(scope) { _, audioSamples ->
            onEnrollSample(audioSamples, wasEnabled)
        }
    }

    fun handleEnrollClear() {
        speakerVerifier.clearEnrollment()
        _hudState.update { it.copy(enrolling = false, enrollProgress = 0) }
        addSystemMessage("Speaker enrollment cleared")
    }

    private fun onWakeWordDetected(keyword: String, audioSamples: FloatArray) {
        Log.i(TAG, "Wake word detected: '$keyword'")

        // Speaker verification
        val verified = speakerVerifier.verify(audioSamples)
        if (!verified) {
            Log.i(TAG, "Speaker verification failed — ignoring")
            addSystemMessage("Wake: voice not recognized")
            return
        }

        Log.i(TAG, "Speaker verified! Starting ASR + photo...")
        addSystemMessage("Listening...")

        // Stop wake word detection, start ASR
        wakeWordEngine.stop()

        // Capture photo simultaneously for 小C
        pendingPhoto = null
        try {
            wakeScreen()
            cameraCapture.capture { base64 ->
                if (base64 != null) {
                    pendingPhoto = base64
                    Log.i(TAG, "Photo captured for 小C (${base64.length} chars)")
                } else {
                    Log.w(TAG, "Photo capture failed — will send text only")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Photo capture error: ${e.message}")
        }

        handleListenStart()
    }

    private fun onEnrollSample(audioSamples: FloatArray, restoreWakeWord: Boolean) {
        val complete = speakerVerifier.processEnrollment(audioSamples)
        val progress = speakerVerifier.enrollProgress
        _hudState.update { it.copy(enrollProgress = progress) }

        if (complete) {
            wakeWordEngine.stop()
            _hudState.update { it.copy(enrolling = false) }
            addSystemMessage("Enrollment complete!")
            if (restoreWakeWord) {
                handleWakeEnable()
            }
        } else if (!speakerVerifier.isEnrolling) {
            // Enrollment was cancelled
            wakeWordEngine.stop()
            if (restoreWakeWord) handleWakeEnable()
        } else {
            addSystemMessage("Say '小C' (${progress}/${speakerVerifier.enrollNeeded})")
        }
    }

    /**
     * Called when ASR final result is received in wake-word-triggered mode.
     * Keep recording so user can continue the conversation.
     * Recording only stops when a sleep command arrives from VPS.
     */
    fun onAsrComplete() {
        Log.i(TAG, "ASR complete — continuing to listen for next utterance")
    }

    private fun initModels() {
        scope.launch(Dispatchers.IO) {
            val modelsDir = File(filesDir, "models")
            if (!modelsDir.exists()) {
                Log.i(TAG, "Models dir not found: $modelsDir — push models via ADB")
                withContext(Dispatchers.Main) {
                    addSystemMessage("No models. Push to ${modelsDir.absolutePath}")
                }
                return@launch
            }

            var ok = true
            if (!wakeWordEngine.init(modelsDir)) {
                Log.e(TAG, "KWS init failed")
                ok = false
            }
            if (!speakerVerifier.init(modelsDir)) {
                Log.w(TAG, "Speaker verifier init failed (non-fatal)")
                // Speaker verification is optional
            }

            modelsReady = ok
            withContext(Dispatchers.Main) {
                if (ok) {
                    addSystemMessage("Models loaded. Use WAKE_ENABLE to activate.")
                    _hudState.update { it.copy(modelsLoaded = true) }
                } else {
                    addSystemMessage("Model loading failed")
                }
            }
        }
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
            addAction("com.superbrain.glasses.WAKE_ENABLE")
            addAction("com.superbrain.glasses.WAKE_DISABLE")
            addAction("com.superbrain.glasses.ENROLL_START")
            addAction("com.superbrain.glasses.ENROLL_CLEAR")
            addAction("com.superbrain.glasses.BROWSER")
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
        scope.launch {
            wsClient.connected.collect { connected ->
                _hudState.update { it.copy(isConnected = connected) }
                updateNotification(if (connected) "Connected" else "Disconnected")
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
                // On final ASR result, send chat.send to VPS with text + pending photo
                if (event.isFinal && event.text.isNotBlank()) {
                    val photo = pendingPhoto
                    pendingPhoto = null
                    val attachments = if (photo != null) {
                        listOf(mapOf("mimeType" to "image/jpeg", "content" to photo))
                    } else null
                    wsClient.sendChat(event.text, attachments)
                    Log.i(TAG, "Sent chat.send: text=${event.text.take(50)}, hasPhoto=${photo != null}")
                }
                // If wake-word mode, continue listening for next utterance
                if (event.isFinal && wakeWordEnabled) {
                    onAsrComplete()
                }
            }
        }

        // WiFi events
        scope.launch {
            wsClient.wifiEvents.collect { event ->
                Log.i(TAG, "WiFi event received: ${event.ssid}")
                handleWifi(event.ssid, event.password)
            }
        }

        // Command events (e.g. sleep → stop listening, restart wake word)
        scope.launch {
            wsClient.commandEvents.collect { event ->
                if (event.action == "sleep") {
                    Log.i(TAG, "Sleep command received — stopping listen, restarting wake word")
                    handleListenStop()
                    _hudState.update { it.copy(wakeWordActive = wakeWordEnabled) }
                    if (wakeWordEnabled && !wakeWordEngine.isRunning.value) {
                        wakeWordEngine.start(scope) { keyword, audioSamples ->
                            onWakeWordDetected(keyword, audioSamples)
                        }
                    }
                    addSystemMessage("Sleeping... say '小C' to wake")
                }
            }
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Network available")
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
        wakeWordEngine.cleanup()
        speakerVerifier.cleanup()
        wsClient.disconnect()
        audioCapture.cleanup()
        cameraCapture.cleanup()
        ttsPlayer.cleanup()
        otaUpdater.cleanup()
        wifiController.cleanup()
        scope.cancel()
    }
}
