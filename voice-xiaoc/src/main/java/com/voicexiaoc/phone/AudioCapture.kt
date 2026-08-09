package com.voicexiaoc.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Microphone capture. Records PCM 16-bit mono 16kHz and emits base64 chunks.
 *
 * Ported from superbrain-glasses/AudioCapture.kt. Unlike the Rokid build (which
 * was forced to CAMCORDER due to a hardware AI-denoise quirk), the phone uses
 * VOICE_RECOGNITION with hardware NS/AEC/AGC — the right source for near-field
 * speech on standard Android phones.
 *
 * NOTE: This is wired but not started in P1c (no wake-word / streaming ASR yet).
 * It exists so the later voice pipeline can drop straight in.
 */
class AudioCapture(private val context: Context) {

    companion object {
        private const val TAG = "AudioCapture"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_DURATION_MS = 100  // 3200 bytes @ 16kHz/16bit
    }

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var recordJob: Job? = null
    private var ns: NoiseSuppressor? = null
    private var aec: AcousticEchoCanceler? = null
    private var agc: AutomaticGainControl? = null

    /**
     * Stream raw PCM 16-bit LE mono 16kHz chunks (~[CHUNK_DURATION_MS] each).
     * This is the path the Tencent streaming ASR client consumes.
     */
    fun startPcm(scope: CoroutineScope, onPcm: (pcm: ByteArray) -> Unit) {
        start(scope, onRaw = onPcm, onChunk = null)
    }

    fun start(scope: CoroutineScope, onChunk: (base64Pcm: String) -> Unit) {
        start(scope, onRaw = null, onChunk = onChunk)
    }

    private fun start(
        scope: CoroutineScope,
        onRaw: ((ByteArray) -> Unit)?,
        onChunk: ((String) -> Unit)?,
    ) {
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
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, CHANNEL, ENCODING,
                bufferSize * 2
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord?.release(); audioRecord = null
                return
            }

            val sessionId = audioRecord!!.audioSessionId
            if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            if (AutomaticGainControl.isAvailable()) agc = AutomaticGainControl.create(sessionId)?.apply { enabled = true }

            audioRecord?.startRecording()
            _isRecording.value = true
            Log.i(TAG, "Recording started")

            val chunkBytes = (SAMPLE_RATE * CHUNK_DURATION_MS / 1000) * 2
            recordJob = scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(chunkBytes)
                while (isActive && _isRecording.value) {
                    val n = audioRecord?.read(buffer, 0, chunkBytes) ?: -1
                    if (n > 0) {
                        val data = if (n == chunkBytes) buffer.copyOf() else buffer.copyOf(n)
                        onRaw?.invoke(data)
                        onChunk?.invoke(Base64.encodeToString(data, Base64.NO_WRAP))
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied", e)
        }
    }

    fun stop() {
        _isRecording.value = false
        recordJob?.cancel(); recordJob = null
        try { ns?.release() } catch (_: Exception) {}
        try { aec?.release() } catch (_: Exception) {}
        try { agc?.release() } catch (_: Exception) {}
        ns = null; aec = null; agc = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        audioRecord?.release(); audioRecord = null
        Log.i(TAG, "Recording stopped")
    }

    fun cleanup() = stop()
}
