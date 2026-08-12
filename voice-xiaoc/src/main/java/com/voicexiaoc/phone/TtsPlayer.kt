package com.voicexiaoc.phone

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.media.MediaPlayer
import android.os.Build
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Audio playback for AI responses. Server-synthesized audio only.
 *
 * The on-device Android TTS fallback was dropped in v0.4.2 ("语音只用豆包") —
 * its engine object lingered here unused until 2026-08-10, holding a system TTS
 * service for nothing. A reply that arrives without audio is now just shown on
 * screen rather than read out in the wrong (flat) voice.
 */
class TtsPlayer(private val context: Context) {

    companion object {
        private const val TAG = "TtsPlayer"
    }

    private var mediaPlayer: MediaPlayer? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    /**
     * 待播队列。网关把长回复切成句子逐段下发（tts_audio 带 seq/final），到齐一段
     * 播一段，首字入耳就不用等整段合成完（实测长回复 8.95s → 3.51s）。
     *
     * 队列必须在这一层，不能让每条消息各自 play —— 旧的 [playBase64] 一上来就
     * stop()，第二段到达会把第一段掐掉，结果是只听得到最后一句。
     *
     * 排队的判断逻辑在 [PlaybackQueue]（纯 Kotlin，有单测）；这里只管把音频
     * 喂给 MediaPlayer。
     */
    private val queue = PlaybackQueue<Segment>()
    private var onDrained: (() -> Unit)? = null

    private class Segment(val base64: String, val format: String)

    /**
     * Forwarded to VoiceXiaocService -> ws.sendLog, since Android's own Log.*
     * is invisible once the phone is out on the road — this is the only way
     * to diagnose "TTS silently didn't play" reports after the fact (e.g.
     * audio focus denied by a car head unit, output routed to an unexpected
     * device, media volume at 0).
     */
    var onLog: ((level: String, msg: String) -> Unit)? = null

    private val playbackAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** Request transient audio focus so playback isn't silently dropped by whatever else owns the output (car head unit, nav app, etc). */
    private fun requestFocus(): Boolean {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(playbackAttrs)
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        val vol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        onLog?.invoke(if (granted) "info" else "warn", "audio focus granted=$granted musicVol=$vol/$maxVol")
        return granted
    }

    /**
     * 播报实际从哪个设备出去 —— 别再靠读代码推断音频路由。
     *
     * 关心这个是因为「播报时能不能同时听」的方案选择完全取决于它：
     * A2DP(媒体路,立体声宽带) 下硬件 AEC 拿不到参考信号,只能用唤醒词打断；
     * 若要真 barge-in 就得切到 SCO/HFP(通话路),但那会把车机拽进"通话模式",
     * 音质掉到 8kHz 还压掉导航播报。所以先量清楚现在在哪条路上。
     */
    private fun logRouting(mp: MediaPlayer) {
        val route = try {
            when (mp.routedDevice?.type) {
                null -> "unknown"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙A2DP(媒体路·立体声)"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙SCO/HFP(通话路·窄带)"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "手机扬声器"
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB音频"
                else -> "type=${mp.routedDevice?.type}"
            }
        } catch (e: Exception) {
            "routing query failed: ${e.message}"
        }
        onLog?.invoke("info", "audio route=$route a2dp=${audioManager.isBluetoothA2dpOn} sco=${audioManager.isBluetoothScoOn}")
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    /**
     * Decode a base64 audio blob (mp3/wav) to a temp file and play it.
     *
     * [onDone] fires exactly once, on natural completion, playback error, or
     * decode/setup failure — the caller (VoiceXiaocService) uses it to know
     * when it's safe to re-open the mic without picking up our own voice
     * (half-duplex: mic is paused by the caller for the duration of playback,
     * since AEC alone did not reliably prevent the phone hearing its own TTS
     * and re-transcribing it, causing a self-talk echo loop).
     */
    fun playBase64(base64: String, format: String = "mp3", onDone: (() -> Unit)? = null) =
        enqueue(base64, format, isFinal = true, onDone = onDone)

    /**
     * 把一段音频排进队列；空闲时立刻开播，正在播就等前一段放完。
     *
     * [isFinal] 标记这是本轮最后一段。**队列空了不等于说完了** —— 网络慢于播放
     * 是常态，中间空一下下一段就到了；只有播完带 final 的那段才算说完，才回调
     * [onDone]（Service 拿它重开麦做跟进）。整段模式（不分句）isFinal 恒为 true，
     * 行为与从前完全一致。
     */
    fun enqueue(base64: String, format: String = "mp3", isFinal: Boolean, onDone: (() -> Unit)? = null) {
        if (base64.isBlank()) { if (isFinal) onDone?.invoke(); return }
        if (onDone != null) onDrained = onDone
        if (queue.enqueue(Segment(base64, format), isFinal)) {
            // 焦点在整轮的首尾各要一次。**每段都申请会让车机在段间反复 ducking**，
            // 接缝立刻就听出来了 —— 分句播报的全部意义就是让人听不出接缝。
            requestFocus()
            playNext()
        }
    }

    private fun playNext() {
        val seg = queue.next()
        if (seg == null) {
            // 队列见底：可能是说完了，也可能只是下一段还在路上（见 PlaybackQueue）
            if (queue.isRoundDone()) finishRound()
            return
        }
        try {
            val bytes = Base64.decode(seg.base64, Base64.DEFAULT)
            // 文件名带序号：同名文件会被下一段覆写，而 MediaPlayer 还在读它。
            val file = File(context.cacheDir, "vx-tts-${fileSeq++ % 4}.${seg.format}")
            FileOutputStream(file).use { it.write(bytes) }
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(playbackAttrs)
                setDataSource(file.absolutePath)
                setOnCompletionListener { mp ->
                    mp.release(); if (mediaPlayer === mp) mediaPlayer = null
                    playNext()
                }
                setOnErrorListener { mp, what, extra ->
                    onLog?.invoke("error", "MediaPlayer error what=$what extra=$extra")
                    mp.release(); if (mediaPlayer === mp) mediaPlayer = null
                    playNext()   // 一段坏了就跳过它，别让整轮哑掉
                    true
                }
                prepare()
                start()
                if (fileSeq == 1L) logRouting(this)   // 路由每轮记一次就够
            }
            onLog?.invoke("info", "playing ${bytes.size}B ${seg.format}, 队列剩 ${queue.size}")
        } catch (e: Exception) {
            onLog?.invoke("error", "播放段失败: ${e.message}")
            playNext()
        }
    }

    private var fileSeq = 0L

    /** 一轮说完：放掉焦点、复位状态、通知调用方可以重开麦了。 */
    private fun finishRound() {
        fileSeq = 0
        abandonFocus()
        onLog?.invoke("info", "tts playback completed")
        val cb = onDrained
        onDrained = null
        cb?.invoke()
    }

    /**
     * Play a bundled raw-resource audio clip (e.g. res/raw/wake_ack.mp3, a
     * pre-synthesized Doubao "我在" clip) with zero network round-trip —
     * used for the instant wake acknowledgment, where waiting on a live
     * Doubao TTS call would defeat the purpose of "instant".
     */
    fun playRaw(resId: Int, onDone: (() -> Unit)? = null) {
        try {
            stop()
            requestFocus()
            val mp = MediaPlayer.create(context, resId, playbackAttrs, audioManager.generateAudioSessionId())
            if (mp == null) {
                abandonFocus()
                onDone?.invoke()
                return
            }
            mediaPlayer = mp.apply {
                setOnCompletionListener { p -> abandonFocus(); p.release(); if (mediaPlayer === p) mediaPlayer = null; onLog?.invoke("info", "wake ack playback completed"); onDone?.invoke() }
                setOnErrorListener { p, what, extra ->
                    onLog?.invoke("error", "MediaPlayer(raw) error what=$what extra=$extra")
                    abandonFocus(); p.release(); if (mediaPlayer === p) mediaPlayer = null; onDone?.invoke(); true
                }
                start()
            }
            onLog?.invoke("info", "playing wake ack clip")
        } catch (e: Exception) {
            onLog?.invoke("error", "playRaw failed: ${e.message}")
            onDone?.invoke()
        }
    }

    /**
     * 立刻闭嘴。**必须连队列一起清空** —— 打断时只停当前这段，后面排着的还会
     * 接着播，表现就是"喊了停，它顿一下又自顾自说下去"。
     *
     * 不回调 [onDrained]：这是被打断，不是说完了；调用方（状态机）自己已经
     * 决定了下一步去哪，不该再收到一个"播完了，去开跟进窗口"的通知。
     */
    fun stop() {
        val pending = queue.clear()
        fileSeq = 0
        onDrained = null
        try { mediaPlayer?.stop(); mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
        abandonFocus()
        if (pending > 0) onLog?.invoke("info", "打断播报，丢弃排队中的 $pending 段")
    }

    fun cleanup() = stop()
}
