package com.voicexiaoc.phone

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 唤醒状态机的行为测试。纯逻辑、不碰 Android，普通 JVM 上就能跑：
 *     ./gradlew :voice-xiaoc:testDebugUnitTest
 *
 * 每个用例都对应一个真实踩过的坑，测试名写的就是那个坑。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WakeMachineTest {

    private class Rig(scope: kotlinx.coroutines.CoroutineScope) {
        val mic = mutableListOf<WakeMachine.MicOwner>()
        val commands = mutableListOf<String>()
        var asrOpen = false
        lateinit var m: WakeMachine

        init {
            m = WakeMachine(
                scope = scope,
                armTimeoutMs = 5000,
                silenceSubmitMs = 2500,
                onMic = { mic.add(it) },
                onOpenAsr = { asrOpen = true },
                onCloseAsr = { asrOpen = false },
                onCommand = { commands.add(it) },
                onState = { },
            )
        }
        val currentMic get() = mic.lastOrNull()
    }

    @Test
    fun `唤醒前麦克风归本地KWS 音频不出手机`() = runTest {
        val r = Rig(this)
        r.m.start()
        assertEquals(WakeMachine.MicOwner.KWS, r.currentMic)
        assertTrue("未唤醒时不该开云端 ASR", !r.asrOpen)
        r.m.stop()
    }

    @Test
    fun `唤醒后才开云端ASR`() = runTest {
        val r = Rig(this)
        r.m.start()
        r.m.onWake()
        assertEquals(WakeMachine.MicOwner.ASR, r.currentMic)
        assertTrue(r.asrOpen)
        r.m.stop()
    }

    @Test
    fun `说完停顿够久 命令被提交且立刻闭麦`() = runTest {
        val r = Rig(this)
        r.m.start(); r.m.onWake()
        r.m.onHeard("明天天气怎么样", isFinal = true)
        advanceTimeBy(2600)
        assertEquals(listOf("明天天气怎么样"), r.commands)
        assertEquals("等 CC 回复期间没有理由继续听", WakeMachine.MicOwner.NONE, r.currentMic)
        assertTrue("提交后应关闭云端 ASR", !r.asrOpen)
        r.m.stop()
    }

    /** 回归：播报期间麦克风必须关着，否则 ASR 听见自己的 TTS → 自激循环。 */
    @Test
    fun `播报期间绝不开麦 防自激循环`() = runTest {
        val r = Rig(this)
        r.m.start(); r.m.onWake()
        r.m.onHeard("你好", isFinal = true)
        advanceTimeBy(2600)
        r.m.onSpeaking()
        assertEquals(WakeMachine.MicOwner.NONE, r.currentMic)
        // 播报期间即使 ASR 回调漏进来，也不该被当成用户说话
        r.m.onHeard("这是小C自己的声音", isFinal = true)
        advanceTimeBy(10_000)
        assertEquals("播报期间收到的文本不该变成新命令", 1, r.commands.size)
        assertEquals(WakeMachine.MicOwner.NONE, r.currentMic)
        r.m.stop()
    }

    /** 回归：播报结束后必须回到能听见唤醒词的状态，历史上这里会永久变聋。 */
    @Test
    fun `播报结束后跟进窗口超时 能回到常听而不是变聋`() = runTest {
        val r = Rig(this)
        r.m.start(); r.m.onWake()
        r.m.onHeard("你好", isFinal = true)
        advanceTimeBy(2600)
        r.m.onSpeaking()
        r.m.onSpoken()
        assertEquals("跟进窗口里麦克风归 ASR", WakeMachine.MicOwner.ASR, r.currentMic)
        advanceTimeBy(5100)   // 窗口内一直没开口
        assertEquals("超时必须回到本地 KWS，否则唤醒词再也叫不醒",
            WakeMachine.MicOwner.KWS, r.currentMic)
        assertTrue(!r.asrOpen)
        r.m.stop()
    }

    @Test
    fun `一直没开口 唤醒窗口超时回常听`() = runTest {
        val r = Rig(this)
        r.m.start(); r.m.onWake()
        advanceTimeBy(5100)
        assertEquals(WakeMachine.MicOwner.KWS, r.currentMic)
        assertTrue(r.commands.isEmpty())
        r.m.stop()
    }

    @Test
    fun `多段识别结果会合并成一句 而不是拆成多条命令`() = runTest {
        val r = Rig(this)
        r.m.start(); r.m.onWake()
        r.m.onHeard("你看看我眼前的", isFinal = true)
        advanceTimeBy(1000)                       // 不到静音阈值
        r.m.onHeard("这个东西", isFinal = true)
        advanceTimeBy(2600)
        assertEquals(listOf("你看看我眼前的这个东西"), r.commands)
        r.m.stop()
    }

    @Test
    fun `只说了唤醒词没说内容 不产生空命令`() = runTest {
        val r = Rig(this)
        r.m.start(); r.m.onWake()
        r.m.onHeard("。", isFinal = true)
        advanceTimeBy(2600)
        assertTrue("纯标点不该当成命令发出去", r.commands.isEmpty())
        r.m.stop()
    }

    @Test
    fun `出错时从任何状态都能安全回到常听`() = runTest {
        val r = Rig(this)
        r.m.start(); r.m.onWake()
        r.m.onError()
        assertEquals(WakeMachine.MicOwner.KWS, r.currentMic)
        assertTrue(!r.asrOpen)
        r.m.stop()
    }
}
