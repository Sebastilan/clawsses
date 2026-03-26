package com.superbrain.glasses

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebSocket client for SuperBrain VPS server.
 * Implements OpenClaw-compatible protocol (connect → chat.send → chat events).
 */
class WsClient(private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "WsClient"
        private const val RECONNECT_DELAY_MS = 3000L
    }

    // Connection config
    var host: String = ""
    var port: Int = 8011
    var token: String = ""

    private val gson = Gson()
    private val requestId = AtomicInteger(0)
    private var webSocket: WebSocket? = null
    private var activeWs: WebSocket? = null  // Track which WS instance is "current"
    private var authenticated = false
    private var reconnectJob: Job? = null
    private var shouldReconnect = false

    // State
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    // Events: streaming deltas and final messages
    sealed class ChatEvent {
        data class Delta(val sessionKey: String, val runId: String, val text: String) : ChatEvent()
        data class Final(val sessionKey: String, val runId: String, val text: String) : ChatEvent()
        data class Error(val sessionKey: String, val runId: String, val message: String) : ChatEvent()
    }

    private val _chatEvents = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 64)
    val chatEvents = _chatEvents.asSharedFlow()

    private val _statusMessages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val statusMessages = _statusMessages.asSharedFlow()

    // OTA and WiFi events from server
    data class OtaEvent(val url: String, val version: String)
    data class WifiEvent(val ssid: String, val password: String)

    private val _otaEvents = MutableSharedFlow<OtaEvent>(extraBufferCapacity = 4)
    val otaEvents = _otaEvents.asSharedFlow()

    private val _wifiEvents = MutableSharedFlow<WifiEvent>(extraBufferCapacity = 4)
    val wifiEvents = _wifiEvents.asSharedFlow()

    // Pending messages queue for offline buffering
    private data class PendingMessage(val text: String, val attachments: List<Map<String, String>>?)
    private val pendingMessages = mutableListOf<PendingMessage>()

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.SECONDS)  // No timeout for WebSocket
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    fun connect() {
        if (host.isBlank() || token.isBlank()) {
            Log.e(TAG, "Host or token not configured")
            _statusMessages.tryEmit("Error: not configured. Use CONFIG first.")
            return
        }

        shouldReconnect = true
        doConnect()
    }

    private fun doConnect() {
        // Cancel pending reconnect — we're connecting now
        reconnectJob?.cancel()
        reconnectJob = null

        // Close old socket quietly (don't cancel — cancel triggers onFailure)
        val old = webSocket
        webSocket = null
        activeWs = null
        old?.close(1000, "reconnecting")

        val url = "ws://$host:$port"
        Log.i(TAG, "Connecting to $url")
        _statusMessages.tryEmit("Connecting to $url...")

        val request = Request.Builder()
            .url(url)
            .header("Origin", "http://$host")
            .build()

        val newWs = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket opened")
                activeWs = ws
                // Wait for connect.challenge event from server
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (ws !== activeWs) return  // Ignore stale callbacks
                handleMessage(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (ws !== activeWs) {
                    Log.d(TAG, "Ignoring stale failure: ${t.message}")
                    return
                }
                Log.e(TAG, "WebSocket failure: ${t.message}")
                onDisconnected("Connection failed: ${t.message}")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (ws !== activeWs) {
                    Log.d(TAG, "Ignoring stale close: $code $reason")
                    return
                }
                Log.i(TAG, "WebSocket closed: $code $reason")
                onDisconnected("Closed: $reason")
            }
        })
        webSocket = newWs
        activeWs = newWs
    }

    fun disconnect() {
        shouldReconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        authenticated = false
        activeWs = null
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        _connected.value = false
        _statusMessages.tryEmit("Disconnected")
    }

    fun sendChat(text: String, attachments: List<Map<String, String>>? = null) {
        if (!authenticated) {
            // Buffer message for later delivery
            synchronized(pendingMessages) {
                pendingMessages.add(PendingMessage(text, attachments))
            }
            Log.i(TAG, "Buffered message (offline): ${text.take(50)}")
            _statusMessages.tryEmit("Offline — message queued")
            return
        }

        val id = nextId()
        val params = mutableMapOf<String, Any>(
            "sessionKey" to "main",
            "idempotencyKey" to UUID.randomUUID().toString(),
            "message" to text
        )
        if (!attachments.isNullOrEmpty()) {
            params["attachments"] = attachments
        }

        val req = mapOf(
            "type" to "req",
            "id" to id,
            "method" to "chat.send",
            "params" to params
        )
        send(gson.toJson(req))
        Log.i(TAG, "Sent chat.send: ${text.take(50)}")
    }

    private fun handleMessage(raw: String) {
        try {
            val json = JsonParser.parseString(raw).asJsonObject
            val type = json.get("type")?.asString ?: return

            when (type) {
                "event" -> handleEvent(json)
                "res" -> handleResponse(json)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message: ${e.message}")
        }
    }

    private fun handleEvent(json: JsonObject) {
        val event = json.get("event")?.asString ?: return
        val payload = json.getAsJsonObject("payload")

        when (event) {
            "connect.challenge" -> {
                // Server sent challenge, authenticate with token
                sendConnect()
            }
            "chat" -> {
                val state = payload?.get("state")?.asString ?: return
                val runId = payload.get("runId")?.asString ?: ""
                val sessionKey = payload.get("sessionKey")?.asString ?: "main"
                val message = payload.getAsJsonObject("message")
                val content = extractTextContent(message)

                when (state) {
                    "delta" -> _chatEvents.tryEmit(ChatEvent.Delta(sessionKey, runId, content))
                    "final" -> _chatEvents.tryEmit(ChatEvent.Final(sessionKey, runId, content))
                    "error" -> {
                        val errorMsg = payload.get("errorMessage")?.asString ?: "Unknown error"
                        _chatEvents.tryEmit(ChatEvent.Error(sessionKey, runId, errorMsg))
                    }
                }
            }
            "ota_update" -> {
                val url = payload?.get("url")?.asString ?: ""
                val version = payload?.get("version")?.asString ?: ""
                if (url.isNotBlank()) {
                    Log.i(TAG, "OTA update event: version=$version url=$url")
                    _otaEvents.tryEmit(OtaEvent(url, version))
                }
            }
            "wifi_config" -> {
                val ssid = payload?.get("ssid")?.asString ?: ""
                val password = payload?.get("password")?.asString ?: ""
                if (ssid.isNotBlank()) {
                    Log.i(TAG, "WiFi config event: ssid=$ssid")
                    _wifiEvents.tryEmit(WifiEvent(ssid, password))
                }
            }
            "heartbeat" -> { /* ignore */ }
        }
    }

    private fun handleResponse(json: JsonObject) {
        val ok = json.get("ok")?.asBoolean ?: false
        val id = json.get("id")?.asString ?: ""

        if (id.startsWith("connect-")) {
            if (ok) {
                // Cancel any pending reconnect — we're connected
                reconnectJob?.cancel()
                reconnectJob = null
                authenticated = true
                _connected.value = true
                _statusMessages.tryEmit("Connected & authenticated")
                Log.i(TAG, "Authenticated successfully")
                flushPendingMessages()
            } else {
                val error = json.getAsJsonObject("error")?.get("message")?.asString ?: "Auth failed"
                _statusMessages.tryEmit("Auth failed: $error")
                Log.e(TAG, "Auth failed: $error")
            }
        } else if (id.startsWith("chat-")) {
            if (ok) {
                val payload = json.getAsJsonObject("payload")
                val runId = payload?.get("runId")?.asString
                Log.i(TAG, "Chat ack: runId=$runId")
            } else {
                val error = json.getAsJsonObject("error")?.get("message")?.asString ?: "Send failed"
                _statusMessages.tryEmit("Send failed: $error")
            }
        }
    }

    private fun sendConnect() {
        val id = "connect-${UUID.randomUUID().toString().take(8)}"
        val req = mapOf(
            "type" to "req",
            "id" to id,
            "method" to "connect",
            "params" to mapOf(
                "auth" to mapOf("token" to token),
                "client" to mapOf(
                    "id" to "superbrain-glasses",
                    "mode" to "ui"
                ),
                "scopes" to listOf("operator.admin")
            )
        )
        send(gson.toJson(req))
        Log.i(TAG, "Sent connect request")
    }

    private fun extractTextContent(message: JsonObject?): String {
        if (message == null) return ""
        val content = message.get("content") ?: return ""
        // Content can be a string or array of {type: "text", text: "..."}
        return if (content.isJsonArray) {
            content.asJsonArray
                .filter { it.isJsonObject && it.asJsonObject.get("type")?.asString == "text" }
                .joinToString("") { it.asJsonObject.get("text")?.asString ?: "" }
        } else {
            content.asString
        }
    }

    private fun onDisconnected(reason: String) {
        authenticated = false
        _connected.value = false
        _statusMessages.tryEmit(reason)

        if (shouldReconnect) {
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(RECONNECT_DELAY_MS)
                Log.i(TAG, "Reconnecting...")
                doConnect()
            }
        }
    }

    private fun flushPendingMessages() {
        val messages: List<PendingMessage>
        synchronized(pendingMessages) {
            messages = pendingMessages.toList()
            pendingMessages.clear()
        }
        if (messages.isNotEmpty()) {
            Log.i(TAG, "Flushing ${messages.size} pending messages")
            _statusMessages.tryEmit("Sending ${messages.size} queued message(s)...")
            for (msg in messages) {
                sendChat(msg.text, msg.attachments)
            }
        }
    }

    private fun nextId(): String = "chat-${requestId.incrementAndGet()}"

    private fun send(json: String) {
        webSocket?.send(json) ?: Log.w(TAG, "WebSocket not connected, can't send")
    }

    fun getStatus(): String {
        return buildString {
            append("host=$host:$port")
            append(", connected=${_connected.value}")
            append(", authenticated=$authenticated")
        }
    }
}
