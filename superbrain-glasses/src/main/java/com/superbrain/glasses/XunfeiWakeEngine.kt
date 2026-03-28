package com.superbrain.glasses

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * 讯飞离线语音唤醒引擎（MSC SDK）。
 *
 * 使用反射调用讯飞SDK，这样在SDK未集成时也能编译通过。
 * SDK集成后会自动生效。
 *
 * ## 集成步骤（用户需要完成）：
 * 1. 在讯飞开放平台创建应用，获取 APPID
 * 2. 下载离线唤醒SDK（Android版）
 * 3. 将 libs/Msc.jar 放到 superbrain-glasses/libs/
 * 4. 将 jniLibs/arm64-v8a/libmsc.so 放到 superbrain-glasses/src/main/jniLibs/arm64-v8a/
 * 5. 将唤醒词资源 .jet 文件放到 superbrain-glasses/src/main/assets/ivw/{APPID}.jet
 * 6. 在 SuperBrainService.kt 中设置 XUNFEI_APPID = "你的APPID"
 * 7. build.gradle.kts 添加: implementation(files("libs/Msc.jar"))
 */
class XunfeiWakeEngine(private val context: Context) {

    companion object {
        private const val TAG = "XunfeiWakeEngine"
        private const val SAMPLE_RATE = 16000

        // 唤醒词灵敏度：0-3000，越大越灵敏但误唤醒越多
        // 推荐值：1450(默认)，眼镜近场可以用1300-1500
        private const val IVW_THRESHOLD = "0:1450"
    }

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private var sdkAvailable = false
    private var wakeuper: Any? = null  // VoiceWakeuper instance via reflection
    private var onWakeWord: ((keyword: String, audioSamples: FloatArray) -> Unit)? = null

    // Ring buffer for speaker verification (last 2s of audio)
    private var ringBuffer = FloatArray(SAMPLE_RATE * 2)
    private var ringPos = 0

    // Self-managed AudioRecord for ring buffer (讯飞SDK管理自己的麦克风)
    private var audioRecord: AudioRecord? = null
    private var ringJob: Job? = null

    /**
     * 初始化讯飞SDK。
     * @param appId 讯飞开放平台的APPID
     * @return true=成功, false=SDK不可用或初始化失败
     */
    fun init(appId: String): Boolean {
        try {
            // 检查讯飞SDK是否存在
            val utilityClass = Class.forName("com.iflytek.cloud.SpeechUtility")
            val constantClass = Class.forName("com.iflytek.cloud.SpeechConstant")

            // SpeechUtility.createUtility(context, "appid=xxx")
            val appIdField = constantClass.getField("APPID")
            val appIdKey = appIdField.get(null) as String
            val createMethod = utilityClass.getMethod("createUtility", Context::class.java, String::class.java)
            createMethod.invoke(null, context, "$appIdKey=$appId")

            // VoiceWakeuper.createWakeuper(context, initListener)
            val wakeuperClass = Class.forName("com.iflytek.cloud.VoiceWakeuper")
            val initListenerClass = Class.forName("com.iflytek.cloud.InitListener")

            // Create InitListener proxy
            val initProxy = java.lang.reflect.Proxy.newProxyInstance(
                initListenerClass.classLoader,
                arrayOf(initListenerClass)
            ) { _, method, args ->
                if (method.name == "onInit") {
                    val code = args?.get(0) as? Int ?: -1
                    Log.i(TAG, "Xunfei SDK init result: $code")
                    if (code != 0) {
                        Log.e(TAG, "Xunfei SDK init failed with code $code")
                    }
                }
                null
            }

            val createWakeuper = wakeuperClass.getMethod("createWakeuper", Context::class.java, initListenerClass)
            wakeuper = createWakeuper.invoke(null, context, initProxy)

            if (wakeuper == null) {
                Log.e(TAG, "createWakeuper returned null")
                return false
            }

            // Configure wake-up parameters
            configureWakeuper(appId)

            sdkAvailable = true
            Log.i(TAG, "Xunfei wake engine initialized (appId=$appId)")
            return true

        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "Xunfei SDK not found — add Msc.jar and libmsc.so to project")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Xunfei init failed: ${e.message}", e)
            return false
        }
    }

    private fun configureWakeuper(appId: String) {
        val w = wakeuper ?: return
        val wakeuperClass = w.javaClass
        val setParam = wakeuperClass.getMethod("setParameter", String::class.java, String::class.java)
        val constantClass = Class.forName("com.iflytek.cloud.SpeechConstant")

        fun getConst(name: String): String = constantClass.getField(name).get(null) as String

        // Reset all params
        setParam.invoke(w, getConst("PARAMS"), null)

        // Wake-up mode (not oneshot)
        setParam.invoke(w, getConst("IVW_SST"), "wakeup")

        // .jet resource path — try assets first
        val resPath = buildResPath(appId)
        setParam.invoke(w, getConst("IVW_RES_PATH"), resPath)
        Log.i(TAG, "IVW_RES_PATH = $resPath")

        // Threshold
        setParam.invoke(w, getConst("IVW_THRESHOLD"), IVW_THRESHOLD)

        // Continuous mode
        setParam.invoke(w, getConst("KEEP_ALIVE"), "1")
    }

    private fun buildResPath(appId: String): String {
        // Try using ResourceUtil if available
        try {
            val utilClass = Class.forName("com.iflytek.cloud.util.ResourceUtil")
            val resTypeClass = Class.forName("com.iflytek.cloud.util.ResourceUtil\$RESOURCE_TYPE")
            val assetsType = resTypeClass.getField("assets").get(null)
            val genMethod = utilClass.getMethod("generateResourcePath", Context::class.java, resTypeClass, String::class.java)
            return genMethod.invoke(null, context, assetsType, "ivw/$appId.jet") as String
        } catch (_: Exception) {}

        // Fallback: try alternate package path
        try {
            val utilClass = Class.forName("com.iflytek.cloud.utils.ResourceUtil")
            val resTypeClass = Class.forName("com.iflytek.cloud.utils.ResourceUtil\$RESOURCE_TYPE")
            val assetsType = resTypeClass.getField("assets").get(null)
            val genMethod = utilClass.getMethod("generateResourcePath", Context::class.java, resTypeClass, String::class.java)
            return genMethod.invoke(null, context, assetsType, "ivw/$appId.jet") as String
        } catch (_: Exception) {}

        // Last resort: direct assets path format
        return "fo|res/ivw/$appId.jet"
    }

    /**
     * Start wake word detection.
     * @param scope Coroutine scope for ring buffer recording
     * @param onDetected Callback with (keyword, audioSamples for speaker verification)
     */
    fun start(scope: CoroutineScope, onDetected: (keyword: String, audioSamples: FloatArray) -> Unit) {
        if (_isRunning.value || !sdkAvailable) return
        val w = wakeuper ?: return

        onWakeWord = onDetected
        _isRunning.value = true

        // Start ring buffer recording for speaker verification
        // (讯飞SDK自己管理麦克风，我们另开一个AudioRecord纯为抓ring buffer)
        // 注意：Android允许多个AudioRecord同时录音，但可能有设备兼容性问题
        // 如果有冲突，可改为在讯飞回调里用writeAudio的方式共享音频
        startRingBuffer(scope)

        // Create WakeuperListener proxy
        val listenerClass = Class.forName("com.iflytek.cloud.WakeuperListener")
        val listenerProxy = java.lang.reflect.Proxy.newProxyInstance(
            listenerClass.classLoader,
            arrayOf(listenerClass)
        ) { _, method, args ->
            when (method.name) {
                "onResult" -> {
                    // WakeuperResult.getResultString() -> JSON
                    val result = args?.get(0) ?: return@newProxyInstance null
                    try {
                        val getStr = result.javaClass.getMethod("getResultString")
                        val json = getStr.invoke(result) as? String ?: ""
                        Log.i(TAG, "Wake result: $json")
                        val obj = JSONObject(json)
                        val score = obj.optInt("score", 0)
                        val id = obj.optInt("id", 0)
                        val keyword = "万象"  // 唤醒词名称，根据.jet文件中配置的

                        Log.i(TAG, "Wake word detected: keyword=$keyword, score=$score, id=$id")

                        // Extract ring buffer for speaker verification
                        val audio = extractRingBuffer()
                        scope.launch(Dispatchers.Main) {
                            onWakeWord?.invoke(keyword, audio)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Wake result parse error: ${e.message}")
                    }
                }
                "onError" -> {
                    val error = args?.get(0)
                    try {
                        val getCode = error?.javaClass?.getMethod("getErrorCode")
                        val getDesc = error?.javaClass?.getMethod("getErrorDescription")
                        val code = getCode?.invoke(error)
                        val desc = getDesc?.invoke(error)
                        Log.e(TAG, "Wake error: code=$code, desc=$desc")
                    } catch (e: Exception) {
                        Log.e(TAG, "Wake error: $error")
                    }
                    // Auto-restart on error if still supposed to be running
                    if (_isRunning.value) {
                        scope.launch {
                            delay(1000)
                            if (_isRunning.value) {
                                Log.i(TAG, "Restarting wake detection after error")
                                try {
                                    val startMethod = w.javaClass.getMethod("startListening", listenerClass)
                                    startMethod.invoke(w, this@newProxyInstance)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Restart failed: ${e.message}")
                                }
                            }
                        }
                    }
                }
                "onBeginOfSpeech" -> {
                    Log.d(TAG, "Wake: begin of speech")
                }
                "onVolumeChanged" -> {
                    // Ignore volume changes
                }
                "onEvent" -> {
                    // Used in oneshot mode, we don't use it
                }
            }
            null
        }

        // VoiceWakeuper.startListening(listener)
        try {
            val startMethod = w.javaClass.getMethod("startListening", listenerClass)
            startMethod.invoke(w, listenerProxy)
            Log.i(TAG, "Xunfei wake detection started")
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed: ${e.message}", e)
            _isRunning.value = false
        }
    }

    fun stop() {
        _isRunning.value = false
        ringJob?.cancel()
        ringJob = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null

        val w = wakeuper ?: return
        try {
            val cancelMethod = w.javaClass.getMethod("cancel")
            cancelMethod.invoke(w)
            Log.i(TAG, "Xunfei wake detection stopped")
        } catch (e: Exception) {
            Log.e(TAG, "cancel failed: ${e.message}")
        }
    }

    fun cleanup() {
        stop()
        val w = wakeuper ?: return
        try {
            val destroyMethod = w.javaClass.getMethod("destroy")
            destroyMethod.invoke(w)
        } catch (_: Exception) {}
        wakeuper = null
        sdkAvailable = false
    }

    // ── Ring buffer for speaker verification ──

    private fun startRingBuffer(scope: CoroutineScope) {
        ringPos = 0
        ringBuffer = FloatArray(SAMPLE_RATE * 2)

        ringJob = scope.launch(Dispatchers.IO) {
            val bufSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(3200 * 4)

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
                    Log.w(TAG, "Ring buffer AudioRecord init failed — speaker verify may not work")
                    return@launch
                }
                audioRecord = recorder
                recorder.startRecording()

                val buf = ShortArray(1600)
                while (isActive && _isRunning.value) {
                    val read = recorder.read(buf, 0, buf.size)
                    if (read > 0) {
                        for (i in 0 until read) {
                            ringBuffer[ringPos % ringBuffer.size] = buf[i] / 32768.0f
                            ringPos++
                        }
                    }
                }
                recorder.stop()
                recorder.release()
            } catch (e: Exception) {
                Log.w(TAG, "Ring buffer error: ${e.message}")
            } finally {
                audioRecord = null
            }
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
}
