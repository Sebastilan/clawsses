package com.superbrain.glasses

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Microphone audio capture. Records PCM 16-bit mono 16kHz,
 * sends base64-encoded chunks via callback.
 */
class AudioCapture(private val context: Context) {

    companion object {
        private const val TAG = "AudioCapture"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_DURATION_MS = 100  // 100ms chunks = 3200 bytes @ 16kHz/16bit
    }

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var recordJob: Job? = null

    /**
     * Start recording and send PCM chunks via onChunk callback.
     * Each chunk is base64-encoded PCM 16-bit mono 16kHz.
     */
    fun start(scope: CoroutineScope, onChunk: (base64Pcm: String) -> Unit) {
        if (_isRecording.value) return

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            return
        }

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid buffer size: $bufferSize")
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER,  // wider pickup range for room audio
                SAMPLE_RATE, CHANNEL, ENCODING,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord?.release()
                audioRecord = null
                return
            }

            audioRecord?.startRecording()
            _isRecording.value = true
            Log.i(TAG, "Recording started")

            val chunkSamples = SAMPLE_RATE * CHUNK_DURATION_MS / 1000
            val chunkBytes = chunkSamples * 2  // 16-bit = 2 bytes per sample

            recordJob = scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(chunkBytes)
                while (isActive && _isRecording.value) {
                    val bytesRead = audioRecord?.read(buffer, 0, chunkBytes) ?: -1
                    if (bytesRead > 0) {
                        val data = if (bytesRead == chunkBytes) buffer else buffer.copyOf(bytesRead)
                        val b64 = Base64.encodeToString(data, Base64.NO_WRAP)
                        onChunk(b64)
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied", e)
        }
    }

    fun stop() {
        _isRecording.value = false
        recordJob?.cancel()
        recordJob = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
        audioRecord?.release()
        audioRecord = null
        Log.i(TAG, "Recording stopped")
    }

    fun cleanup() {
        stop()
    }
}
