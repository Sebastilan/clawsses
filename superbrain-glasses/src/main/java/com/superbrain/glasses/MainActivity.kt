package com.superbrain.glasses

import android.Manifest
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * SuperBrain Glasses — main activity.
 * All control via ADB broadcast intents (com.superbrain.glasses.*).
 * Connects to VPS via WebSocket, displays AI responses on 480x640 green HUD.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "SuperBrain"
        private const val PERMISSION_REQUEST_CODE = 100

        @Volatile
        var instance: MainActivity? = null
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val hudState = MutableStateFlow(HudState(statusText = "SuperBrain"))

    lateinit var wsClient: WsClient
        private set
    lateinit var cameraCapture: CameraCapture
        private set
    lateinit var audioCapture: AudioCapture
        private set
    lateinit var ttsPlayer: TtsPlayer
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Initialize components
        wsClient = WsClient(scope)
        cameraCapture = CameraCapture(this)
        audioCapture = AudioCapture(this)
        ttsPlayer = TtsPlayer(this)

        // Collect WebSocket events
        collectWsEvents()

        // Request permissions
        requestPermissionsIfNeeded()

        // Set Compose content
        setContent {
            HudScreen(hudState)
        }

        Log.i(TAG, "SuperBrain Glasses started. Waiting for ADB commands.")
        addSystemMessage("Ready. Use ADB broadcast to configure & connect.")
    }

    private fun collectWsEvents() {
        // Chat events (streaming)
        scope.launch {
            wsClient.chatEvents.collect { event ->
                when (event) {
                    is WsClient.ChatEvent.Delta -> {
                        hudState.update { state ->
                            val messages = state.messages.toMutableList()
                            // Update or add streaming message
                            val lastIdx = messages.indexOfLast { it.role == "assistant" && it.isStreaming }
                            if (lastIdx >= 0) {
                                messages[lastIdx] = messages[lastIdx].copy(content = event.text)
                            } else {
                                messages.add(HudMessage("assistant", event.text, isStreaming = true))
                            }
                            state.copy(
                                messages = messages,
                                isStreaming = true,
                                streamingText = event.text
                            )
                        }
                    }
                    is WsClient.ChatEvent.Final -> {
                        hudState.update { state ->
                            val messages = state.messages.toMutableList()
                            val lastIdx = messages.indexOfLast { it.role == "assistant" && it.isStreaming }
                            if (lastIdx >= 0) {
                                messages[lastIdx] = messages[lastIdx].copy(
                                    content = event.text,
                                    isStreaming = false
                                )
                            } else {
                                messages.add(HudMessage("assistant", event.text))
                            }
                            state.copy(
                                messages = messages,
                                isStreaming = false,
                                streamingText = ""
                            )
                        }
                        // TTS: speak the final response
                        ttsPlayer.speak(event.text)
                    }
                    is WsClient.ChatEvent.Error -> {
                        hudState.update { state ->
                            val messages = state.messages.toMutableList()
                            // Close any streaming message
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
            }
        }

        // Connection state
        scope.launch {
            wsClient.connected.collect { connected ->
                hudState.update { it.copy(isConnected = connected) }
            }
        }
    }

    // ── ADB command handlers ──

    fun handleConfig(host: String, port: Int, token: String) {
        wsClient.host = host
        wsClient.port = port
        wsClient.token = token
        Log.i(TAG, "Configured: $host:$port")
        addSystemMessage("Config: $host:$port")
    }

    fun handleConnect() {
        wsClient.connect()
    }

    fun handleDisconnect() {
        wsClient.disconnect()
    }

    fun handleSend(text: String) {
        // Add user message to HUD
        hudState.update { state ->
            state.copy(messages = state.messages + HudMessage("user", text))
        }
        wsClient.sendChat(text)
    }

    fun handlePhoto() {
        addSystemMessage("Capturing photo...")
        cameraCapture.capture { base64 ->
            scope.launch(Dispatchers.Main) {
                if (base64 != null) {
                    addSystemMessage("Photo captured, sending to AI...")
                    val attachments = listOf(
                        mapOf(
                            "mimeType" to "image/jpeg",
                            "content" to base64
                        )
                    )
                    hudState.update { state ->
                        state.copy(messages = state.messages + HudMessage("user", "[Photo sent]"))
                    }
                    wsClient.sendChat("Describe what you see in this image.", attachments)
                } else {
                    addSystemMessage("Photo capture failed")
                }
            }
        }
    }

    fun handleListenStart() {
        hudState.update { it.copy(isListening = true) }
        audioCapture.start(scope) { base64Pcm ->
            // For now, accumulate audio chunks. In the future, stream to VPS ASR.
            // Current implementation: just capture, user sends via SEND after stopping.
            Log.d(TAG, "Audio chunk: ${base64Pcm.length} chars")
        }
        addSystemMessage("Listening...")
    }

    fun handleListenStop() {
        audioCapture.stop()
        hudState.update { it.copy(isListening = false) }
        addSystemMessage("Stopped listening")
    }

    fun handleDisplay(text: String) {
        addSystemMessage(text)
    }

    fun handleStatus() {
        val status = buildString {
            appendLine("=== SuperBrain Status ===")
            appendLine("WS: ${wsClient.getStatus()}")
            appendLine("Audio: recording=${audioCapture.isRecording.value}")
            appendLine("Camera: capturing=${cameraCapture.isCapturing}")
            appendLine("TTS: enabled=${ttsPlayer.enabled}")
            appendLine("Messages: ${hudState.value.messages.size}")
        }
        Log.i(TAG, status)
        addSystemMessage(status.trim())
    }

    private fun addSystemMessage(text: String) {
        hudState.update { state ->
            val messages = state.messages.toMutableList()
            // Limit system messages to avoid bloat
            if (messages.size > 100) {
                messages.removeAt(0)
            }
            messages.add(HudMessage("system", text))
            state.copy(messages = messages, statusText = text.take(40))
        }
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
        instance = null
        wsClient.disconnect()
        audioCapture.cleanup()
        cameraCapture.cleanup()
        ttsPlayer.cleanup()
        scope.cancel()
    }
}
