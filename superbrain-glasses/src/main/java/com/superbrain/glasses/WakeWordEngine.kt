package com.superbrain.glasses

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Always-on wake word detection using Sherpa-onnx KWS.
 * Listens for "小C" keyword and triggers callback.
 * Runs on a background thread with minimal CPU usage.
 */
class WakeWordEngine(private val context: Context) {

    companion object {
        private const val TAG = "WakeWordEngine"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        // Process 100ms frames for KWS
        private const val FRAME_SAMPLES = 1600  // 100ms @ 16kHz
        private const val FRAME_BYTES = FRAME_SAMPLES * 2  // 16-bit = 2 bytes/sample
    }

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private var keywordSpotter: KeywordSpotter? = null
    private var audioRecord: AudioRecord? = null
    private var detectJob: Job? = null
    private var onWakeWord: ((keyword: String, audioSamples: FloatArray) -> Unit)? = null

    /**
     * Initialize KWS engine. Models must be in [modelsDir].
     * Expected files:
     *   kws/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx
     *   kws/decoder-epoch-12-avg-2-chunk-16-left-64.onnx
     *   kws/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx
     *   kws/tokens.txt
     *   kws/keywords.txt
     */
    fun init(modelsDir: File): Boolean {
        val kwsDir = File(modelsDir, "kws")
        if (!kwsDir.exists()) {
            Log.e(TAG, "KWS model dir not found: $kwsDir")
            return false
        }

        val encoder = File(kwsDir, "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx")
        val decoder = File(kwsDir, "decoder-epoch-12-avg-2-chunk-16-left-64.onnx")
        val joiner = File(kwsDir, "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx")
        val tokens = File(kwsDir, "tokens.txt")
        val keywords = File(kwsDir, "keywords.txt")

        if (!encoder.exists() || !decoder.exists() || !joiner.exists() || !tokens.exists()) {
            Log.e(TAG, "KWS model files missing in $kwsDir")
            return false
        }

        // Create keywords.txt if not exists with default "小C" keyword
        if (!keywords.exists()) {
            createDefaultKeywords(keywords, tokens)
        }

        try {
            val config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = encoder.absolutePath,
                        decoder = decoder.absolutePath,
                        joiner = joiner.absolutePath,
                    ),
                    tokens = tokens.absolutePath,
                    numThreads = 1,
                    provider = "cpu",
                    modelType = "zipformer2",
                ),
                keywordsFile = keywords.absolutePath,
                keywordsScore = 1.0f,
                keywordsThreshold = 0.25f,
                numTrailingBlanks = 1,
                maxActivePaths = 4,
            )
            keywordSpotter = KeywordSpotter(null, config)
            Log.i(TAG, "KWS initialized successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "KWS init failed: ${e.message}", e)
            return false
        }
    }

    /**
     * Start always-on wake word detection.
     * [onDetected] is called with the keyword and the audio samples around detection.
     */
    fun start(scope: CoroutineScope, onDetected: (keyword: String, audioSamples: FloatArray) -> Unit) {
        if (_isRunning.value) return
        val spotter = keywordSpotter ?: run {
            Log.e(TAG, "KWS not initialized")
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            return
        }

        onWakeWord = onDetected
        _isRunning.value = true

        detectJob = scope.launch(Dispatchers.IO) {
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
                .coerceAtLeast(FRAME_BYTES * 4)

            try {
                @Suppress("MissingPermission")
                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, CHANNEL, ENCODING,
                    bufferSize
                )

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord init failed")
                    _isRunning.value = false
                    return@launch
                }

                audioRecord = recorder
                recorder.startRecording()
                Log.i(TAG, "Wake word detection started")

                val stream = spotter.createStream()
                val buffer = ShortArray(FRAME_SAMPLES)
                // Ring buffer to keep last 2 seconds of audio for speaker verification
                val ringBuffer = FloatArray(SAMPLE_RATE * 2) // 2 seconds
                var ringPos = 0

                while (isActive && _isRunning.value) {
                    val shortsRead = recorder.read(buffer, 0, FRAME_SAMPLES)
                    if (shortsRead > 0) {
                        val samples = FloatArray(shortsRead) { buffer[it] / 32768.0f }

                        // VAD: skip KWS inference during silence to save CPU
                        val rms = kotlin.math.sqrt(samples.map { it * it }.average().toFloat())
                        if (rms < 0.01f) continue

                        // Update ring buffer
                        for (s in samples) {
                            ringBuffer[ringPos % ringBuffer.size] = s
                            ringPos++
                        }

                        stream.acceptWaveform(samples, SAMPLE_RATE)
                        while (spotter.isReady(stream)) {
                            spotter.decode(stream)
                        }

                        val result = spotter.getResult(stream)
                        if (result.keyword.isNotBlank()) {
                            Log.i(TAG, "Wake word detected: '${result.keyword}'")
                            // Extract last 2s of audio for speaker verification
                            val audioForVerify = extractRingBuffer(ringBuffer, ringPos)
                            withContext(Dispatchers.Main) {
                                onWakeWord?.invoke(result.keyword, audioForVerify)
                            }
                        }
                    }
                }

                stream.release()
                recorder.stop()
                recorder.release()
            } catch (e: Exception) {
                Log.e(TAG, "Wake word detection error: ${e.message}", e)
            } finally {
                audioRecord = null
                _isRunning.value = false
                Log.i(TAG, "Wake word detection stopped")
            }
        }
    }

    fun stop() {
        _isRunning.value = false
        detectJob?.cancel()
        detectJob = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }

    fun cleanup() {
        stop()
        keywordSpotter?.release()
        keywordSpotter = null
    }

    private fun extractRingBuffer(ring: FloatArray, pos: Int): FloatArray {
        val len = ring.size
        val result = FloatArray(len)
        for (i in 0 until len) {
            result[i] = ring[(pos - len + i + len * 2) % len]
        }
        return result
    }

    /**
     * Create default keywords.txt with "小C" keyword.
     * The wenetspeech model uses ppinyin tokens.
     */
    private fun createDefaultKeywords(keywordsFile: File, tokensFile: File) {
        // For wenetspeech ppinyin model, "小C" phonetically = "xiǎo sī"
        // We provide multiple phonetic variants for better detection
        val keywords = buildString {
            appendLine("x iǎo s ī @小C")
        }
        keywordsFile.writeText(keywords)
        Log.i(TAG, "Created default keywords.txt")
    }
}
