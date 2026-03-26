package com.superbrain.glasses

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Android TTS engine wrapper for speaking AI responses aloud.
 */
class TtsPlayer(context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "TtsPlayer"
    }

    private val tts = TextToSpeech(context, this)
    private var ready = false
    var enabled = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.CHINESE)
            ready = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            if (!ready) {
                // Fallback to English
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
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "sb-${System.currentTimeMillis()}")
    }

    fun stop() {
        tts.stop()
    }

    fun cleanup() {
        tts.stop()
        tts.shutdown()
    }
}
