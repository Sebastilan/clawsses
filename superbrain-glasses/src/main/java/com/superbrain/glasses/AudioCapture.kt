package com.superbrain.glasses

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
    private var ns: NoiseSuppressor? = null
    private var aec: AcousticEchoCanceler? = null
    private var agc: AutomaticGainControl? = null

    /**
     * Start recording and send PCM chunks via onChunk callback.
     * Each chunk is base64-encoded PCM 16-bit mono 16kHz.
     *
     * @param mode "conversation" → VOICE_RECOGNITION (hw NS/AEC, near-field, rejects far-field noise)
     *             "ambient"      → CAMCORDER (wider pickup, captures room/scene audio)
     */
    fun start(scope: CoroutineScope, mode: String = "conversation", onChunk: (base64Pcm: String) -> Unit) {
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

        // Rokid 硬件：VOICE_RECOGNITION 实测收不到音频（疑似 AI 降噪链路问题）
        // 暂时统一用 CAMCORDER，背景噪声问题改在服务端置信度/时序过滤
        val source = MediaRecorder.AudioSource.CAMCORDER
        Log.i(TAG, "AudioRecord mode=$mode source=$source (Rokid forced CAMCORDER)")

        try {
            audioRecord = AudioRecord(
                source,
                SAMPLE_RATE, CHANNEL, ENCODING,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord?.release()
                audioRecord = null
                return
            }

            // 按模式挂/不挂 AudioEffect（硬件软件混合降噪）
            val sessionId = audioRecord!!.audioSessionId
            if (mode == "conversation") {
                val nsOk = NoiseSuppressor.isAvailable()
                val aecOk = AcousticEchoCanceler.isAvailable()
                val agcOk = AutomaticGainControl.isAvailable()
                if (nsOk) ns = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                if (aecOk) aec = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                if (agcOk) agc = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
                Log.i(TAG, "AudioEffect mode=conversation NS=$nsOk/${ns?.enabled} AEC=$aecOk/${aec?.enabled} AGC=$agcOk/${agc?.enabled}")
            } else {
                Log.i(TAG, "AudioEffect mode=$mode: no effects attached (raw capture)")
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
        try { ns?.release() } catch (_: Exception) {}
        try { aec?.release() } catch (_: Exception) {}
        try { agc?.release() } catch (_: Exception) {}
        ns = null; aec = null; agc = null
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
