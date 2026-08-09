package com.voicexiaoc.phone

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Live integration test for [TencentAsrClient] against the real Tencent Cloud
 * streaming ASR endpoint (`wss://asr.cloud.tencent.com/asr/v2/...`).
 *
 * Feeds the bundled clip `src/test/resources/test_zh.mp3` (edge-TTS of the
 * Chinese sentence "帮我看看今天的日程安排") frame-by-frame and asserts the
 * recognized transcript comes back non-empty and contains the expected words.
 *
 * This exercises the exact production code path — HMAC-SHA1 URL signing, the WS
 * handshake, binary audio streaming, the `{"type":"end"}` marker, and the
 * result-frame JSON parsing. Production streams raw PCM (voiceFormat=1); the
 * only difference here is the format code (8=mp3), so the fragile parts are the
 * same bytes.
 *
 * Requires network access and Tencent credentials via env
 * (`TENCENT_SECRET_ID` / `TENCENT_SECRET_KEY` / `TENCENT_APPID`, default APPID
 * 1317727798). Skipped (not failed) when credentials are absent.
 *
 * Run:  ./gradlew :voice-xiaoc:testDebugUnitTest --tests '*TencentAsrIntegrationTest*'
 */
class TencentAsrIntegrationTest {

    private fun cred(name: String, default: String = ""): String =
        System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

    @Test
    fun signedUrl_isDeterministic_andWellFormed() {
        val url = TencentAsrClient.buildSignedUrl(
            secretId = "AKIDxxxxx", secretKey = "sk", appId = "1317727798",
            engine = "16k_zh", voiceFormat = 1, vadSilenceMs = 800,
            timestamp = 1_700_000_000L, nonce = 12345L,
            voiceId = "fixed-voice-id",
        )
        assertTrue(url.startsWith("wss://asr.cloud.tencent.com/asr/v2/1317727798?"))
        assertTrue(url.contains("engine_model_type=16k_zh"))
        assertTrue(url.contains("voice_format=1"))
        assertTrue(url.contains("&signature="))
        // Params must be lexicographically sorted (secretId signing invariant).
        assertTrue(url.indexOf("engine_model_type=") < url.indexOf("expired="))
        assertTrue(url.indexOf("needvad=") < url.indexOf("nonce="))
    }

    @Test
    fun recognizesChineseSentence() {
        val secretId = cred("TENCENT_SECRET_ID")
        val secretKey = cred("TENCENT_SECRET_KEY")
        val appId = cred("TENCENT_APPID", "1317727798")
        assumeTrue("Tencent creds not set — skipping live ASR test",
            secretId.isNotBlank() && secretKey.isNotBlank())

        val audio = javaClass.classLoader!!.getResourceAsStream("test_zh.mp3")!!.readBytes()
        assertTrue("test audio missing", audio.isNotEmpty())

        val sb = StringBuilder()
        val done = CountDownLatch(1)
        val err = AtomicReference<String?>(null)

        val client = TencentAsrClient(secretId, secretKey, appId,
            engine = "16k_zh", voiceFormat = 8 /* mp3 */)

        client.start(object : TencentAsrClient.Listener {
            override fun onReady() {
                // Stream in ~100ms-ish frames, pacing lightly like a live mic.
                Thread {
                    var off = 0
                    val chunk = 3200
                    while (off < audio.size) {
                        val end = minOf(off + chunk, audio.size)
                        client.sendPcm(audio.copyOfRange(off, end))
                        off = end
                        Thread.sleep(40)
                    }
                    client.finish()
                }.start()
            }
            override fun onPartial(text: String) { println("[partial] $text") }
            override fun onFinal(text: String) { sb.append(text); println("[final] $text") }
            override fun onCompleted() { done.countDown() }
            override fun onError(msg: String) { err.set(msg); done.countDown() }
        })

        val finished = done.await(30, TimeUnit.SECONDS)
        val result = sb.toString().trim()
        println("==== ASR RESULT: \"$result\" ====")

        assertTrue("ASR errored: ${err.get()}", err.get() == null)
        assertTrue("ASR did not complete in time", finished)
        assertTrue("empty transcript", result.isNotEmpty())
        assertTrue("unexpected transcript: $result",
            result.contains("日程") || result.contains("今天"))
    }
}
