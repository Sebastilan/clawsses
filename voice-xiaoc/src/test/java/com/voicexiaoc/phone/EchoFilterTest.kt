package com.voicexiaoc.phone

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回声过滤 —— 唤醒应答那声"我在"不能被当成他的命令。
 *
 * v0.9.0 起唤醒后会播一声"我在"，而**麦克风在这一声响的时候就已经开了**
 * （故意的：等它放完再开麦，他一张嘴第一个字就丢了）。所以 ASR 必然听得见它。
 *
 * 漏掉的后果很具体：他喊完唤醒词还没开口，小C 已经先自问自答了一轮 ——
 * 而且那一轮会占满他的注意力，等他真开口时对面正在说话。
 */
class EchoFilterTest {

    private fun machine(onCommand: (String) -> Unit) = WakeMachine(
        scope = CoroutineScope(Dispatchers.Unconfined),
        armTimeoutMs = 5000, silenceSubmitMs = 1200,
        onMic = {}, onOpenAsr = {}, onCloseAsr = {},
        onCommand = onCommand, onStopSpeaking = {}, onState = {},
    )

    private fun submitted(vararg finals: String): List<String> {
        val got = mutableListOf<String>()
        val m = machine { got.add(it) }
        m.start()
        m.onWake()
        finals.forEach { m.onHeard(it, isFinal = true) }
        m.submit()
        return got
    }

    @Test
    fun `唤醒应答我在不能当成命令 —— 否则他还没开口小C就自问自答了`() {
        assertEquals("不该发出任何命令", emptyList<String>(), submitted("我在"))
        assertEquals(emptyList<String>(), submitted("我在。"))
    }

    @Test
    fun `唤醒词单独成句也要扔 —— ASR 常把那一声单独出一个final`() {
        assertEquals(emptyList<String>(), submitted("健康顺利"))
        assertEquals(emptyList<String>(), submitted("顺利健康"))
    }

    @Test
    fun `纯标点碎片照旧扔`() {
        assertEquals(emptyList<String>(), submitted("。"))
        assertEquals(emptyList<String>(), submitted("，，"))
    }

    @Test
    fun `真话不能被误伤 —— 含唤醒词的正常句子要照发`() {
        assertEquals(listOf("健康顺利，帮我记个事"), submitted("健康顺利，帮我记个事"))
        assertTrue(submitted("我在开车呢").isNotEmpty())   // "我在开车" 不是回声
        assertTrue(submitted("查一下天气").isNotEmpty())
    }
}
