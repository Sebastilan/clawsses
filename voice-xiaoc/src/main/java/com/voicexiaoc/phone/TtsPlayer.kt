package com.voicexiaoc.phone

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Audio playback for AI responses.
 *
 * Ported from superbrain-glasses/TtsPlayer.kt. Two paths:
 *  - [speak]      : on-device Android TTS for plain `text_reply` frames.
 *  - [playBase64] : decode+play a base64 audio blob from a `tts_audio` frame
 *                   (server-synthesized MP3, matching super-brain's approach).
 */
class TtsPlayer(private val context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "TtsPlayer"
    }

    private val tts = TextToSpeech(context, this)
    private var ready = false
    private var mediaPlayer: MediaPlayer? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    /** When true, `text_reply` is spoken via on-device TTS. */
    var enabled = true

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

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.CHINESE)
            ready = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            if (!ready) {
                tts.setLanguage(Locale.US)
                ready = true
            }
            Log.i(TAG, "TTS initialized, ready=$ready")
        } else {
            Log.e(TAG, "TTS init failed: $status")
        }
    }

    fun speak(text: String) {
        if (!enabled || !ready || text.isBlank()) return
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "vx-${System.currentTimeMillis()}")
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
        tts.stop()
        try { mediaPlayer?.stop(); mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
        abandonFocus()
    }

    fun cleanup() {
        stop()
        tts.shutdown()
    }
}
