package com.voicexiaoc.phone

import android.util.Log
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Tencent Cloud **real-time streaming** ASR client (WebSocket v2 API).
 *
 * Ported from super-brain's `asr_websocket.py` (`wss://asr.cloud.tencent.com/asr/v2/<APPID>`,
 * HMAC-SHA1 signed URL). Phone microphone PCM is streamed frame-by-frame; the
 * server returns interim (`slice_type` 0/1) and per-sentence final (`slice_type` 2)
 * results, then `final=1` when the stream ends.
 *
 * Credentials are injected via BuildConfig / ConfigStore — never hardcoded.
 *
 * Usage:
 * ```
 * val asr = TencentAsrClient(secretId, secretKey, appId)
 * asr.start(object : TencentAsrClient.Listener {
 *     override fun onReady()            { /* begin feeding PCM */ }
 *     override fun onPartial(t: String) { /* interim text */ }
 *     override fun onFinal(t: String)   { /* one sentence done */ }
 *     override fun onCompleted()        { /* server sent final=1 */ }
 *     override fun onError(m: String)   { }
 * })
 * asr.sendPcm(pcmChunk)   // repeat while recording
 * asr.finish()            // stop capture -> server flushes remaining
 * ```
 */
class TencentAsrClient(
    private val secretId: String,
    private val secretKey: String,
    private val appId: String,
    /** Recognition engine. `16k_zh` = 16kHz Mandarin (default). */
    private val engine: String = "16k_zh",
    /** Audio format code: 1=PCM, 4=speex, 6=silk, 8=mp3, 10=opus, 12=wav, 14=m4a. */
    private val voiceFormat: Int = 1,
    /** VAD silence duration (ms) that ends a sentence. */
    private val vadSilenceMs: Int = 800,
) {

    companion object {
        private const val TAG = "TencentAsrClient"
        private const val HOST = "asr.cloud.tencent.com"

        /**
         * Build the HMAC-SHA1-signed `wss://` URL. Pure/deterministic given a
         * fixed [nonce]/[voiceId]/[timestamp] — extracted so it is unit-testable.
         *
         * Mirrors `build_ws_url()` in super-brain/asr_websocket.py exactly:
         * params are sorted lexicographically, the sign string is
         * `asr.cloud.tencent.com/asr/v2/<appId>?<sortedQuery>`, HMAC-SHA1 with the
         * secret key, base64, then URL-encoded and appended as `&signature=`.
         */
        fun buildSignedUrl(
            secretId: String,
            secretKey: String,
            appId: String,
            engine: String,
            voiceFormat: Int,
            vadSilenceMs: Int,
            timestamp: Long = System.currentTimeMillis() / 1000,
            nonce: Long = (System.currentTimeMillis() % 1_000_000_000),
            voiceId: String = UUID.randomUUID().toString(),
        ): String {
            val params = sortedMapOf(
                "secretid" to secretId,
                "timestamp" to timestamp.toString(),
                "expired" to (timestamp + 86400).toString(),
                "nonce" to nonce.toString(),
                "engine_model_type" to engine,
                "voice_id" to voiceId,
                "voice_format" to voiceFormat.toString(),
                "needvad" to "1",
                "vad_silence_time" to vadSilenceMs.toString(),
            )
            val query = params.entries.joinToString("&") { "${it.key}=${it.value}" }
            val signStr = "$HOST/asr/v2/$appId?$query"

            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), "HmacSHA1"))
            val raw = mac.doFinal(signStr.toByteArray(Charsets.UTF_8))
            // java.util.Base64 (not android.util.Base64) so signing also works on
            // the plain JVM under unit tests. minSdk 28 => available on Android.
            val sigB64 = java.util.Base64.getEncoder().encodeToString(raw)
            val sigEnc = URLEncoder.encode(sigB64, "UTF-8")

            return "wss://$HOST/asr/v2/$appId?$query&signature=$sigEnc"
        }
    }

    interface Listener {
        /** Handshake accepted — safe to start feeding PCM. */
        fun onReady() {}
        /** Interim (not-yet-final) transcript for the current sentence. */
        fun onPartial(text: String) {}
        /** A sentence has finalized. */
        fun onFinal(text: String) {}
        /** Server acknowledged end-of-stream (`final=1`). */
        fun onCompleted() {}
        fun onError(msg: String) {}
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var listener: Listener? = null
    private val ready = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    val isReady: Boolean get() = ready.get()

    fun start(listener: Listener) {
        this.listener = listener
        ready.set(false); closed.set(false)

        val url = buildSignedUrl(secretId, secretKey, appId, engine, voiceFormat, vadSilenceMs)
        Log.i(TAG, "connecting: ${url.replace(secretId, "***")}")

        val req = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, text: String) = handle(text)

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (closed.getAndSet(true)) return
                Log.e(TAG, "ws failure: ${t.message}")
                listener.onError("ws_failure: ${t.message}")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "ws closed: $code $reason")
            }
        })
    }

    private fun handle(text: String) {
        val l = listener ?: return
        try {
            val json = JsonParser.parseString(text).asJsonObject
            val code = json.get("code")?.asInt ?: -1
            if (code != 0) {
                val msg = json.get("message")?.asString ?: "code=$code"
                Log.w(TAG, "asr error: $msg")
                l.onError("asr_error[$code]: $msg")
                return
            }

            // First code==0 frame with no result is the handshake ack.
            if (!ready.getAndSet(true)) {
                Log.i(TAG, "handshake ok: ${json.get("message")?.asString ?: ""}")
                l.onReady()
            }

            json.getAsJsonObject("result")?.let { r ->
                val slice = r.get("slice_type")?.asInt ?: -1
                val txt = r.get("voice_text_str")?.asString ?: ""
                when (slice) {
                    0, 1 -> if (txt.isNotEmpty()) l.onPartial(txt)
                    2 -> if (txt.isNotEmpty()) l.onFinal(txt)
                }
            }

            if (json.get("final")?.asInt == 1) {
                Log.i(TAG, "stream final")
                l.onCompleted()
                close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "parse error: ${e.message} on: ${text.take(120)}")
        }
    }

    /** Stream one PCM/audio chunk. No-op until [Listener.onReady]. */
    fun sendPcm(bytes: ByteArray) {
        if (!ready.get() || closed.get() || bytes.isEmpty()) return
        webSocket?.send(bytes.toByteString(0, bytes.size))
    }

    /** Signal end-of-audio; server flushes any remaining sentence then `final=1`. */
    fun finish() {
        if (closed.get()) return
        webSocket?.send("{\"type\": \"end\"}")
        Log.i(TAG, "sent end marker")
    }

    /** Abort immediately without waiting for the server flush. */
    fun cancel() = close()

    private fun close() {
        if (closed.getAndSet(true)) return
        try { webSocket?.close(1000, "done") } catch (_: Exception) {}
        webSocket = null
    }
}
