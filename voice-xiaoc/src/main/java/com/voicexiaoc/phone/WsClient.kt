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
        private const val PONG_TIMEOUT_MS = 75_000L   // 2.5 个心跳周期没回音 = 连接已死
        private const val OUTBOX_TTL_MS = 60_000L     // 超过 1 分钟的命令不再补发
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

    /**
     * 最近一次收到 pong 的时刻。移动网络的 NAT 会悄悄掐掉空闲 TCP，形成"半开连接"：
     * socket 看着还在，send() 也不报错(数据进了 OkHttp 缓冲区)，要等 TCP 重传超时
     * 好几分钟才暴露。2026-08-10 实测就是这样丢掉一整句话：手机以为连着，统帅说完
     * 了、也识别出来了，发出去却进了黑洞，网关侧一条日志都没有。
     * 光发 ping 不看回音是查不出来的——之前 pong 收到只打了行 Log.d 就扔了。
     */
    @Volatile private var lastPongAt = 0L

    /**
     * 发件箱：连接不可用时暂存统帅真正说的话(asr_text)，连上就补发。
     * 只存命令，不存日志——日志丢了无所谓，他说的话丢了就是"我说了它没反应"。
     */
    private val outbox = java.util.concurrent.ConcurrentLinkedQueue<Pair<Long, String>>()

    // Connection state (true once `connected` frame received, or socket open if no auth)
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    // Human-readable status line for the UI.
    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()

    // Reply events surfaced to the app (text and/or TTS audio).
    data class ReplyEvent(val id: String?, val text: String, val isEcho: Boolean)
    /**
     * [seq]/[isFinal] 只在网关开了分句流式（XIAOC_TTS_STREAM=1）时才有：一条回复
     * 被切成多段先后下发。整段模式下网关不带这两个字段，此时 seq=null、
     * isFinal=true，行为与从前一致 —— 新旧网关都能对上。
     */
    data class TtsEvent(val id: String?, val format: String, val base64: String, val text: String,
                        val seq: Int? = null, val isFinal: Boolean = true)
    data class PushEvent(val level: String, val text: String, val tts: TtsEvent?)

    private val _replies = MutableSharedFlow<ReplyEvent>(extraBufferCapacity = 32)
    val replies = _replies.asSharedFlow()

    private val _ttsAudio = MutableSharedFlow<TtsEvent>(extraBufferCapacity = 32)  // 分句流式一轮十几段
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
                lastPongAt = System.currentTimeMillis()
                startPingLoop()
                flushOutbox()
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
    fun sendAsrText(text: String, final: Boolean = true, lang: String = "zh-CN"): Boolean {
        val id = "u-${nextId()}"
        // queueIfDown：统帅说的话是唯一不能丢的东西。连接不可用时进发件箱，
        // 3 秒后重连自动补发；只有超过 TTL 才真丢。
        val delivered = send(gson.toJson(mapOf(
            "type" to "asr_text", "id" to id, "ts" to now(),
            "text" to text, "final" to final, "lang" to lang
        )), queueIfDown = true)
        Log.i(TAG, "asr_text delivered=$delivered: ${text.take(50)}")
        return delivered
    }

    /**
     * 上报位置。坐标是 WGS-84（Android 原生），GCJ-02 由网关换算。
     * 不进发件箱：位置是"当前值"，补发一个几分钟前的点没有意义，反而会让
     * CC 拿到过期位置当实时用；丢了等下一个周期就行。
     */
    fun sendLocation(
        lat: Double, lon: Double, accuracy: Float?, speed: Float?,
        bearing: Float?, altitude: Double?, provider: String?, fixedAt: Long,
    ) {
        val m = mutableMapOf<String, Any>(
            "type" to "location", "ts" to now(),
            "lat" to lat, "lon" to lon, "fixedAt" to fixedAt
        )
        accuracy?.let { m["accuracy"] = it }
        speed?.let { m["speed"] = it }
        bearing?.let { m["bearing"] = it }
        altitude?.let { m["altitude"] = it }
        provider?.let { m["provider"] = it }
        send(gson.toJson(m))
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
                        text = json.get("text")?.asString ?: "",
                        seq = json.get("seq")?.asInt,
                        // 老网关不发 final，那就是整段一条 —— 当作末段，否则永远等不到收尾
                        isFinal = json.get("final")?.asBoolean ?: true
                    )
                    // 分句流式下一轮能有十几段，缓冲区满了 tryEmit 会静默丢帧 ——
                    // 表现是"某句话中间少了一截"，不记日志就永远查不出来。
                    if (evt.base64.isNotBlank() && !_ttsAudio.tryEmit(evt)) {
                        Log.w(TAG, "tts_audio 缓冲区满，丢了 seq=${evt.seq}")
                        sendLog("warn", TAG, "tts_audio 缓冲区满，丢了 seq=${evt.seq}")
                    }
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
                "pong" -> lastPongAt = System.currentTimeMillis()
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
                if (!_connected.value) continue
                // 看门狗：连着两个半心跳周期没等到 pong，判定这条连接已经死了(半开)，
                // 主动重连。不这样做的话，要等 TCP 重传超时几分钟才暴露，
                // 而这几分钟里统帅说的每一句都会掉进黑洞。
                val silence = System.currentTimeMillis() - lastPongAt
                if (silence > PONG_TIMEOUT_MS) {
                    Log.w(TAG, "no pong for ${silence}ms — connection is half-open, forcing reconnect")
                    _status.value = "心跳超时，重连中…"
                    onDisconnected("pong timeout")
                    continue
                }
                sendPing()
            }
        }
    }

    /** 连上后把攒着的命令补发出去。太旧的丢弃——迟到几分钟的问题再答就是打扰。 */
    private fun flushOutbox() {
        val now = System.currentTimeMillis()
        var sent = 0
        var stale = 0
        while (true) {
            val (ts, json) = outbox.poll() ?: break
            if (now - ts > OUTBOX_TTL_MS) { stale++; continue }
            webSocket?.send(json); sent++
        }
        if (sent > 0 || stale > 0) {
            Log.i(TAG, "outbox flushed: sent=$sent stale=$stale")
            sendLog("info", "WsClient", "补发离线期间的命令: 成功 $sent 条, 过期丢弃 $stale 条")
        }
    }

    /**
     * 发一帧。[queueIfDown]=true 的帧(统帅说的话)在连接不可用时进发件箱等补发，
     * 其余(日志/心跳)直接丢。
     *
     * @return true = 已交给 socket；false = 没发出去(已入队或已丢弃)
     */
    private fun send(json: String, queueIfDown: Boolean = false): Boolean {
        val ws = webSocket
        if (ws != null && _connected.value) return ws.send(json)
        if (queueIfDown) {
            outbox.add(System.currentTimeMillis() to json)
            Log.w(TAG, "not connected — queued for retry: ${json.take(80)}")
        } else {
            Log.w(TAG, "not connected, dropped: ${json.take(80)}")
        }
        return false
    }

    private fun nextId(): String = msgId.incrementAndGet().toString()
    private fun now(): Long = System.currentTimeMillis()

    fun getStatus(): String = "host=$host:$port connected=${_connected.value}"
}
