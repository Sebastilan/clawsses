package com.superbrain.glasses

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.iflytek.aikit.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 讯飞 AIKit 离线语音唤醒引擎（IVW）。
 *
 * 使用 AIKit.aar SDK 直接调用，替代旧版 MSC SDK 反射方案。
 * 唤醒词资源文件在 assets/ivw/ 下，首次启动时复制到 workDir。
 *
 * SDK 调用流程: initEntry → registerListener → start → write(循环) → end → unInit
 * 注意: engineUnInit 仅在最终退出时调用，调用后不能再 start。
 */
class XunfeiWakeEngine(private val context: Context) {

    companion object {
        private const val TAG = "XunfeiWakeEngine"
        private const val SAMPLE_RATE = 16000
        private const val ABILITY_ID = "e867a88f2"

        // 唤醒灵敏度 0-3000，越大越灵敏但误唤醒越多
        // 格式: "唤醒词索引:阈值"，默认800
        private const val IVW_THRESHOLD = "0 0:800"

        // 音频缓冲区：每次送 1280 bytes = 40ms @16kHz 16bit mono
        private const val AUDIO_BUFFER_SIZE = 1280

        // 唤醒词
        private const val WAKE_WORD = "你好小希"
    }

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private var sdkAvailable = false
    private var sdkInitialized = false
    private var aiHandle: AiHandle? = null
    private val isEnd = AtomicBoolean(true)
    private val isRecording = AtomicBoolean(false)

    private var onWakeWord: ((keyword: String, audioSamples: FloatArray) -> Unit)? = null

    // Ring buffer for speaker verification (last 2s of audio)
    private var ringBuffer = FloatArray(SAMPLE_RATE * 2)
    private var ringPos = 0

    private var audioRecord: AudioRecord? = null
    private var recordJob: Job? = null

    private lateinit var workDir: String
    private lateinit var ivwResDir: String

    /**
     * 初始化讯飞 AIKit SDK。
     * @param appId 讯飞 APPID
     * @param apiKey 讯飞 APIKey
     * @param apiSecret 讯飞 APISecret
     * @return true=成功
     */
    fun init(appId: String, apiKey: String, apiSecret: String): Boolean {
        try {
            workDir = context.getExternalFilesDir(null)?.absolutePath
                ?: (context.filesDir.absolutePath + "/iflytek")
            workDir = "$workDir/iflytek/"
            ivwResDir = "${workDir}ivw"

            // Copy assets/ivw/ to workDir/ivw/
            copyAssetsToWorkDir()

            // Write keyword file
            writeKeywordFile()

            // Set log
            AiHelper.getInst().setLogInfo(LogLvl.VERBOSE, 1, "${workDir}aikit/aeeLog.txt")

            // Build init params
            val params = BaseLibrary.Params.builder()
                .appId(appId)
                .apiKey(apiKey)
                .apiSecret(apiSecret)
                .workDir(workDir)
                .build()

            // Init SDK (synchronous on this thread)
            val latch = CountDownLatch(1)
            var authResult = -1

            AiHelper.getInst().registerListener(object : CoreListener {
                override fun onAuthStateChange(type: ErrType, code: Int) {
                    Log.i(TAG, "Auth state: type=$type, code=$code")
                    if (type == ErrType.AUTH) {
                        authResult = code
                        latch.countDown()
                    }
                }
            })

            // initEntry must be called on a background thread per SDK requirement
            val initThread = Thread {
                AiHelper.getInst().initEntry(context.applicationContext, params)
            }
            initThread.start()

            // Wait for auth callback (max 10s)
            val gotAuth = latch.await(10, TimeUnit.SECONDS)
            if (!gotAuth) {
                Log.w(TAG, "SDK auth timeout — proceeding anyway (may work offline after first auth)")
            } else if (authResult != 0) {
                Log.e(TAG, "SDK auth failed: $authResult")
                // Still proceed — may work offline if previously authed
            } else {
                Log.i(TAG, "SDK auth success")
            }

            // Register IVW ability listener
            AiHelper.getInst().registerListener(ABILITY_ID, ivwListener)

            sdkInitialized = true
            sdkAvailable = true
            Log.i(TAG, "Xunfei AIKit wake engine initialized (appId=$appId)")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Xunfei init failed: ${e.message}", e)
            return false
        }
    }

    /**
     * Start wake word detection.
     * Continuously records audio, feeds to IVW engine, loops on wake detection.
     */
    fun start(scope: CoroutineScope, onDetected: (keyword: String, audioSamples: FloatArray) -> Unit) {
        if (_isRunning.value || !sdkAvailable) return

        onWakeWord = onDetected
        _isRunning.value = true

        // Start recording + feeding audio to IVW
        startRecordingAndDetect(scope)
    }

    fun stop() {
        _isRunning.value = false
        isRecording.set(false)
        recordJob?.cancel()
        recordJob = null

        // End current IVW session if active
        endSession()

        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }

    fun cleanup() {
        stop()
        if (sdkInitialized) {
            try {
                AiHelper.getInst().unInit()
            } catch (_: Exception) {}
            sdkInitialized = false
        }
        sdkAvailable = false
    }

    // ── IVW Session Management ──

    private fun startSession(): Int {
        // Write keyword file (may have been updated)
        writeKeywordFile()

        // Load custom keyword data
        val customBuilder = AiRequest.builder()
        customBuilder.customText("key_word", "$ivwResDir/keyword.txt", 0)
        var ret = AiHelper.getInst().loadData(ABILITY_ID, customBuilder.build())
        if (ret != 0) {
            Log.e(TAG, "loadData failed: $ret")
            return ret
        }

        // Specify keyword dataset
        val indexs = intArrayOf(0)
        ret = AiHelper.getInst().specifyDataSet(ABILITY_ID, "key_word", indexs)
        if (ret != 0) {
            Log.e(TAG, "specifyDataSet failed: $ret")
            return ret
        }

        // Build start params
        val paramBuilder = AiRequest.builder()
        paramBuilder.param("wdec_param_nCmThreshold", IVW_THRESHOLD)
        paramBuilder.param("gramLoad", true)

        isEnd.set(false)
        aiHandle = AiHelper.getInst().start(ABILITY_ID, paramBuilder.build(), null)
        val code = aiHandle?.code ?: -1
        if (code != 0) {
            Log.e(TAG, "IVW start failed: $code")
            isEnd.set(true)
            return code
        }

        Log.i(TAG, "IVW session started")
        return 0
    }

    private fun writeAudio(data: ByteArray, status: AiStatus) {
        if (isEnd.get()) return
        val handle = aiHandle ?: return

        val dataBuilder = AiRequest.builder()
        val aiAudio = AiAudio.get("wav").data(data).status(status).valid()
        dataBuilder.payload(aiAudio)

        val ret = AiHelper.getInst().write(dataBuilder.build(), handle)
        if (ret != 0) {
            Log.w(TAG, "IVW write failed: $ret")
        }
    }

    private fun endSession() {
        if (!isEnd.get()) {
            val handle = aiHandle
            if (handle != null) {
                val ret = AiHelper.getInst().end(handle)
                Log.d(TAG, "IVW session ended: $ret")
            }
            isEnd.set(true)
            aiHandle = null
        }
    }

    // ── IVW Listener ──

    private val ivwListener = object : AiListener {
        override fun onResult(handleID: Int, outputData: List<AiResponse>?, usrContext: Any?) {
            if (outputData.isNullOrEmpty()) return
            for (resp in outputData) {
                val key = resp.key
                val value = String(resp.value ?: byteArrayOf())
                Log.i(TAG, "IVW result: key=$key, value=$value, status=${resp.status}")

                if (key == "func_wake_up" || key == "func_pre_wakeup") {
                    Log.i(TAG, "Wake word detected! key=$key result=$value")

                    // Extract ring buffer audio for speaker verification
                    val audio = extractRingBuffer()

                    // Notify callback on main thread
                    // Need scope reference — store it
                    activeScope?.launch(Dispatchers.Main) {
                        onWakeWord?.invoke(WAKE_WORD, audio)
                    }
                }
            }
        }

        override fun onEvent(handleID: Int, event: Int, outputData: List<AiResponse>?, usrContext: Any?) {
            Log.d(TAG, "IVW event: handleID=$handleID, event=$event")
        }

        override fun onError(handleID: Int, code: Int, msg: String?, usrContext: Any?) {
            Log.e(TAG, "IVW error: handleID=$handleID, code=$code, msg=$msg")
        }
    }

    // ── Recording + Detection Loop ──

    private var activeScope: CoroutineScope? = null

    private fun startRecordingAndDetect(scope: CoroutineScope) {
        activeScope = scope
        ringPos = 0
        ringBuffer = FloatArray(SAMPLE_RATE * 2)

        recordJob = scope.launch(Dispatchers.IO) {
            val bufSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(AUDIO_BUFFER_SIZE * 4)

            try {
                @Suppress("MissingPermission")
                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord init failed")
                    _isRunning.value = false
                    return@launch
                }
                audioRecord = recorder
                isRecording.set(true)

                // Outer loop: restart IVW session on each wake detection or error
                while (isActive && _isRunning.value) {
                    // Start new IVW session
                    val startRet = startSession()
                    if (startRet != 0) {
                        Log.e(TAG, "Failed to start IVW session, retrying in 2s...")
                        delay(2000)
                        continue
                    }

                    // Start recording
                    recorder.startRecording()

                    val buf = ByteArray(AUDIO_BUFFER_SIZE)
                    var isFirst = true

                    // Inner loop: feed audio frames
                    while (isActive && _isRunning.value && !isEnd.get()) {
                        val read = recorder.read(buf, 0, AUDIO_BUFFER_SIZE)
                        if (read > 0) {
                            // Feed to ring buffer for speaker verification
                            feedRingBuffer(buf, read)

                            // Feed to IVW engine
                            val status = if (isFirst) {
                                isFirst = false
                                AiStatus.BEGIN
                            } else {
                                AiStatus.CONTINUE
                            }
                            writeAudio(buf.copyOf(read), status)
                        }

                        // Small delay to match ~40ms per frame
                        // 1280 bytes / (16000 Hz * 2 bytes) = 40ms
                    }

                    // End session before restarting
                    recorder.stop()
                    endSession()

                    if (_isRunning.value) {
                        // Brief pause before restarting session
                        delay(200)
                    }
                }

                isRecording.set(false)
                recorder.release()

            } catch (e: Exception) {
                Log.e(TAG, "Recording error: ${e.message}", e)
                isRecording.set(false)
            } finally {
                audioRecord = null
            }
        }
    }

    // ── Ring Buffer for Speaker Verification ──

    private fun feedRingBuffer(data: ByteArray, length: Int) {
        // Convert PCM16 bytes to float samples for ring buffer
        val numSamples = length / 2
        for (i in 0 until numSamples) {
            val lo = data[i * 2].toInt() and 0xFF
            val hi = data[i * 2 + 1].toInt() and 0xFF
            val sample = (lo or (hi shl 8)).toShort()
            ringBuffer[ringPos % ringBuffer.size] = sample / 32768.0f
            ringPos++
        }
    }

    private fun extractRingBuffer(): FloatArray {
        val len = ringBuffer.size
        val result = FloatArray(len)
        for (i in 0 until len) {
            result[i] = ringBuffer[(ringPos - len + i + len * 2) % len]
        }
        return result
    }

    // ── Asset Management ──

    private fun copyAssetsToWorkDir() {
        val resDir = File(ivwResDir)
        if (!resDir.exists()) resDir.mkdirs()

        // Also create log dir
        File("${workDir}aikit").mkdirs()

        val assetFiles = listOf(
            "IVW_FILLER_1", "IVW_GRAM_1", "IVW_KEYWORD_1", "IVW_MLP_1",
            "keyword1.txt", "keyword1.txt.bin"
        )

        for (name in assetFiles) {
            val target = File(resDir, name)
            if (!target.exists()) {
                try {
                    context.assets.open("ivw/$name").use { input ->
                        FileOutputStream(target).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d(TAG, "Copied asset: ivw/$name → $target")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to copy asset ivw/$name: ${e.message}")
                }
            }
        }
    }

    private fun writeKeywordFile() {
        try {
            val keywordFile = File(ivwResDir, "keyword.txt")
            // Always rewrite to ensure latest wake word
            if (keywordFile.exists()) keywordFile.delete()
            // Also delete bin cache so SDK regenerates
            val binFile = File(ivwResDir, "keyword.bin")
            if (binFile.exists()) binFile.delete()

            val writer = BufferedWriter(OutputStreamWriter(FileOutputStream(keywordFile), "UTF-8"))
            writer.write("$WAKE_WORD;")
            writer.newLine()
            writer.close()
            Log.d(TAG, "Wrote keyword file: $WAKE_WORD")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write keyword file: ${e.message}")
        }
    }
}
