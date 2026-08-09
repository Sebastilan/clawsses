package com.voicexiaoc.phone

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * Local, always-on wake-word spotting — sherpa-onnx zipformer-wenetspeech
 * KWS model (int8, ~5MB), fully on-device, zero network calls.
 *
 * Ported from lgp-tv's ~/attic/2026-07-pre-mcc-cleanup/kws/kws_engine.py —
 * same model, same wake word ("健康顺利"/"顺利健康", see assets/kws-model/keywords.txt),
 * same threshold/score. This replaces the old approach of running continuous
 * cloud ASR + string-matching every final for the wake word (wasteful, and
 * not how anyone else does wake-word detection — see 2026-08 discussion).
 */
class KwsDetector(context: Context) {

    companion object {
        private const val MODEL_DIR = "kws-model"
        private const val SAMPLE_RATE = 16000
    }

    private val spotter: KeywordSpotter = KeywordSpotter(
        assetManager = context.assets,
        config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$MODEL_DIR/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    decoder = "$MODEL_DIR/decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
                    joiner = "$MODEL_DIR/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                ),
                tokens = "$MODEL_DIR/tokens.txt",
                modelType = "zipformer2",
                numThreads = 2,
                provider = "cpu",
            ),
            keywordsFile = "$MODEL_DIR/keywords.txt",
            keywordsScore = 1.0f,
            keywordsThreshold = 0.25f,
            numTrailingBlanks = 2,
        ),
    )

    private var stream = spotter.createStream()

    /**
     * Feed one chunk of 16-bit PCM LE mono 16kHz audio (as produced by
     * [AudioCapture.startPcm]). Returns the matched keyword text on a hit,
     * null otherwise. A hit implicitly resets the internal stream state so
     * the next call starts fresh (mirrors kws_engine.py's KwsDetector.feed).
     *
     * Must fetch getResult() immediately after each decode() inside the loop,
     * not after — a hit is transient and gets overwritten by the next frame's
     * decode if you wait until the whole isReady loop drains (bit us once in
     * the Python version; see kws_engine.py docstring).
     */
    fun feed(pcm: ByteArray): String? {
        val samples = FloatArray(pcm.size / 2)
        for (i in samples.indices) {
            val lo = pcm[2 * i].toInt() and 0xFF
            val hi = pcm[2 * i + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort()
            samples[i] = sample / 32768.0f
        }
        stream.acceptWaveform(samples, SAMPLE_RATE)
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            val result = spotter.getResult(stream)
            if (result.keyword.isNotEmpty()) {
                spotter.reset(stream)
                return result.keyword
            }
        }
        return null
    }

    fun release() {
        stream.release()
        spotter.release()
    }
}
