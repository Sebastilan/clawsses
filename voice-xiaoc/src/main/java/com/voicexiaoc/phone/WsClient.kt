package com.voicexiaoc.phone

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
 * WebSocket client for the voice-xiaoc-gateway.
 *
 * Ported from superbrain-glasses/WsClient.kt but rewritten for the gateway's
 * **flat `type`** protocol (docs/protocol.md) instead of OpenClaw's req/res/event
 * RPC. Each frame is one UTF-8 JSON object with a `type` field.
 *
 * Phone → gateway : connect / asr_text / wake / ping / sleep
 * gateway → phone : connected / echo / text_reply / tts_audio / push / pong / error
 *
 * Auto-reconnects with a fixed delay. Heartbeat ping every 30s.
 */
class WsClient(private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "WsClient"
        private const val RECONNECT_DELAY_MS = 3000L
        private const val PING_INTERVAL_MS = 30_000L
        const val CLIENT_VERSION = "0.1.0"
    }

    // Connection config
    var host: String = ConfigStore.DEFAULT_HOST
    var port: Int = ConfigStore.DEFAULT_PORT
    var token: String = ""
    var deviceId: String = "phone-unknown"

    private val gson = Gson()
    private val msgId = AtomicInteger(0)
    private var webSocket: WebSocket? = null
    private var activeWs: WebSocket? = null
    private var reconnectJob: Job? = null
    private var pingJob: Job? = null
    private var shouldReconnect = false

    // Connection state (true once `connected` frame received, or socket open if no auth)
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    // Human-readable status line for the UI.
    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()

    // Reply events surfaced to the app (text and/or TTS audio).
    data class ReplyEvent(val id: String?, val text: String, val isEcho: Boolean)
    data class TtsEvent(val id: String?, val format: String, val base64: String, val text: String)
    data class PushEvent(val level: String, val text: String, val tts: TtsEvent?)

    private val _replies = MutableSharedFlow<ReplyEvent>(extraBufferCapacity = 32)
    val replies = _replies.asSharedFlow()

    private val _ttsAudio = MutableSharedFlow<TtsEvent>(extraBufferCapacity = 8)
    val ttsAudio = _ttsAudio.asSharedFlow()

    private val _pushes = MutableSharedFlow<PushEvent>(extraBufferCapacity = 8)
    val pushes = _pushes.asSharedFlow()

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.SECONDS)          // no timeout for WebSocket
        .pingInterval(30, TimeUnit.SECONDS)        // OkHttp-level control ping
        .build()

    fun connect() {
        shouldReconnect = true
        doConnect()
    }

    private fun doConnect() {
        reconnectJob?.cancel()
        reconnectJob = null

        val old = webSocket
        webSocket = null
        activeWs = null
        old?.close(1000, "reconnecting")

        val url = "ws://$host:$port"
        Log.i(TAG, "Connecting to $url")
        _status.value = "Connecting to $url…"

        val request = Request.Builder()
            .url(url)
            .header("Origin", "http://$host")
            .build()

        val newWs = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (ws !== activeWs) return
                Log.i(TAG, "WebSocket opened")
                _status.value = "Socket open — handshaking…"
                sendConnect()
                // The gateway may reply `connected`; but even without auth the
                // socket is usable, so optimistically mark connected on open.
                _connected.value = true
                startPingLoop()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (ws !== activeWs) return
                handleMessage(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (ws !== activeWs) return
                Log.e(TAG, "WebSocket failure: ${t.message}")
                onDisconnected("Connection failed: ${t.message}")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (ws !== activeWs) return
                Log.i(TAG, "WebSocket closed: $code $reason")
                onDisconnected("Closed: $reason")
            }
        })
        webSocket = newWs
        activeWs = newWs
    }

    fun disconnect() {
        shouldReconnect = false
        reconnectJob?.cancel(); reconnectJob = null
        pingJob?.cancel(); pingJob = null
        activeWs = null
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        _connected.value = false
        _status.value = "Disconnected"
    }

    // ── Phone → gateway ──────────────────────────────────────────────

    private fun sendConnect() {
        val frame = mutableMapOf<String, Any>(
            "type" to "connect",
            "id" to "c-${nextId()}",
            "deviceId" to deviceId,
            "clientVersion" to CLIENT_VERSION
        )
        if (token.isNotBlank()) frame["token"] = token
        send(gson.toJson(frame))
    }

    /** Report a wake-word hit (P1: not yet triggered; wired for later). */
    fun sendWake(keyword: String, confidence: Double) {
        send(gson.toJson(mapOf(
            "type" to "wake", "ts" to now(), "keyword" to keyword, "confidence" to confidence
        )))
    }

    /** Send a recognized utterance. Gateway only acts on final results. */
    fun sendAsrText(text: String, final: Boolean = true, lang: String = "zh-CN"): String {
        val id = "u-${nextId()}"
        send(gson.toJson(mapOf(
            "type" to "asr_text", "id" to id, "ts" to now(),
            "text" to text, "final" to final, "lang" to lang
        )))
        Log.i(TAG, "Sent asr_text: ${text.take(50)}")
        return id
    }

    /** End the current session (user said "拜拜" / timeout). */
    fun sendSleep(reason: String = "user_bye") {
        send(gson.toJson(mapOf("type" to "sleep", "ts" to now(), "reason" to reason)))
    }

    private fun sendPing() {
        send(gson.toJson(mapOf("type" to "ping", "ts" to now())))
    }

    /**
     * Remote log line — every ASR/audio/lifecycle event and exception gets
     * mirrored here so debugging doesn't depend on screenshots or ADB.
     * Best-effort: silently dropped if the socket isn't connected yet
     * (gateway logs it server-side via journalctl).
     */
    fun sendLog(level: String, tag: String, msg: String) {
        Log.i(TAG, "[remote-log $level/$tag] $msg")
        if (!_connected.value) return
        try {
            send(gson.toJson(mapOf(
                "type" to "log", "ts" to now(), "level" to level, "tag" to tag, "msg" to msg
            )))
        } catch (_: Exception) { /* never let logging crash the app */ }
    }

    // ── gateway → phone ──────────────────────────────────────────────

    private fun handleMessage(raw: String) {
        try {
            val json = JsonParser.parseString(raw).asJsonObject
            when (json.get("type")?.asString ?: return) {
                "connected" -> {
                    _connected.value = true
                    val sid = json.get("sessionId")?.asString ?: "?"
                    _status.value = "Connected (session $sid)"
                    Log.i(TAG, "connected: session=$sid")
                }
                "echo" -> {
                    val id = json.get("id")?.asString
                    val text = json.get("text")?.asString ?: ""
                    _replies.tryEmit(ReplyEvent(id, text, isEcho = true))
                }
                "text_reply" -> {
                    val id = json.get("id")?.asString
                    val text = json.get("text")?.asString ?: ""
                    _replies.tryEmit(ReplyEvent(id, text, isEcho = false))
                }
                "tts_audio" -> {
                    val evt = TtsEvent(
                        id = json.get("id")?.asString,
                        format = json.get("format")?.asString ?: "mp3",
                        base64 = json.get("data")?.asString ?: "",
                        text = json.get("text")?.asString ?: ""
                    )
                    if (evt.base64.isNotBlank()) _ttsAudio.tryEmit(evt)
                }
                "push" -> {
                    val tts = json.getAsJsonObject("tts")?.let {
                        TtsEvent(null, it.get("format")?.asString ?: "mp3",
                            it.get("data")?.asString ?: "", "")
                    }
                    _pushes.tryEmit(PushEvent(
                        level = json.get("level")?.asString ?: "hud",
                        text = json.get("text")?.asString ?: "",
                        tts = tts
                    ))
                }
                "pong" -> Log.d(TAG, "pong")
                "error" -> {
                    val code = json.get("code")?.asString ?: "?"
                    val msg = json.get("message")?.asString ?: ""
                    Log.w(TAG, "gateway error [$code]: $msg")
                    _status.value = "Gateway error [$code]: $msg"
                }
                else -> Log.d(TAG, "ignored frame: $raw")
            }
        } catch (e: Exception) {
            Log.e(TAG, "parse error: ${e.message}")
        }
    }

    // ── plumbing ─────────────────────────────────────────────────────

    private fun onDisconnected(reason: String) {
        _connected.value = false
        _status.value = reason
        pingJob?.cancel(); pingJob = null
        if (shouldReconnect) {
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(RECONNECT_DELAY_MS)
                Log.i(TAG, "Reconnecting…")
                doConnect()
            }
        }
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                delay(PING_INTERVAL_MS)
                if (_connected.value) sendPing()
            }
        }
    }

    private fun send(json: String) {
        webSocket?.send(json) ?: Log.w(TAG, "not connected, dropped: ${json.take(80)}")
    }

    private fun nextId(): String = msgId.incrementAndGet().toString()
    private fun now(): Long = System.currentTimeMillis()

    fun getStatus(): String = "host=$host:$port connected=${_connected.value}"
}
