package com.voicexiaoc.phone

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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
    fun playBase64(base64: String, format: String = "mp3", onDone: (() -> Unit)? = null) {
        if (base64.isBlank()) { onDone?.invoke(); return }
        try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val file = File(context.cacheDir, "vx-tts.$format")
            FileOutputStream(file).use { it.write(bytes) }
            stop()
            requestFocus()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(playbackAttrs)
                setDataSource(file.absolutePath)
                setOnCompletionListener { mp -> abandonFocus(); mp.release(); if (mediaPlayer === mp) mediaPlayer = null; onLog?.invoke("info", "tts playback completed"); onDone?.invoke() }
                setOnErrorListener { mp, what, extra ->
                    onLog?.invoke("error", "MediaPlayer error what=$what extra=$extra")
                    abandonFocus(); mp.release(); if (mediaPlayer === mp) mediaPlayer = null; onDone?.invoke(); true
                }
                prepare()
                start()
            }
            onLog?.invoke("info", "playing ${bytes.size}B $format audio")
        } catch (e: Exception) {
            onLog?.invoke("error", "playBase64 failed: ${e.message}")
            onDone?.invoke()
        }
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

    fun stop() {
        try { mediaPlayer?.stop(); mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
        abandonFocus()
    }

    fun cleanup() = stop()
}
