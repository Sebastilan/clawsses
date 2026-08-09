package com.voicexiaoc.phone

import android.content.Context
import android.media.MediaPlayer
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

    /** When true, `text_reply` is spoken via on-device TTS. */
    var enabled = true

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
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { mp -> mp.release(); if (mediaPlayer === mp) mediaPlayer = null; onDone?.invoke() }
                setOnErrorListener { mp, what, extra ->
                    Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                    mp.release(); if (mediaPlayer === mp) mediaPlayer = null; onDone?.invoke(); true
                }
                prepare()
                start()
            }
            Log.i(TAG, "Playing ${bytes.size}B $format audio")
        } catch (e: Exception) {
            Log.e(TAG, "playBase64 failed: ${e.message}")
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
            val mp = MediaPlayer.create(context, resId)
            if (mp == null) {
                onDone?.invoke()
                return
            }
            mediaPlayer = mp.apply {
                setOnCompletionListener { p -> p.release(); if (mediaPlayer === p) mediaPlayer = null; onDone?.invoke() }
                setOnErrorListener { p, what, extra ->
                    Log.e(TAG, "MediaPlayer(raw) error what=$what extra=$extra")
                    p.release(); if (mediaPlayer === p) mediaPlayer = null; onDone?.invoke(); true
                }
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "playRaw failed: ${e.message}")
            onDone?.invoke()
        }
    }

    fun stop() {
        tts.stop()
        try { mediaPlayer?.stop(); mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
    }

    fun cleanup() {
        stop()
        tts.shutdown()
    }
}
