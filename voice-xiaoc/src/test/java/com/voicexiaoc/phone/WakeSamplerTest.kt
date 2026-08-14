package com.voicexiaoc.phone

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 环形缓冲的回绕逻辑 —— 错了不会崩，只会让样本音频是乱序的碎片，
 * 而那种错要等到人去听样本时才发现，那时阈值已经按错数据调过一轮了。
 */
class WakeSamplerTest {

    /** 200ms @16kHz/16bit = 6400 字节。取小容量方便构造回绕。 */
    private fun sampler() = WakeSampler(preRollMs = 200, sampleRate = 16000)
    private fun bytes(from: Int, n: Int) = ByteArray(n) { ((from + it) % 251).toByte() }

    @Test
    fun `没喂过 → 空`() {
        assertEquals(0, sampler().snapshot().size)
        assertEquals(0, sampler().bufferedMs())
    }

    @Test
    fun `没填满时只给已有的那部分 —— 不能拿零填充冒充音频`() {
        val s = sampler()
        s.feed(bytes(0, 1000))
        assertArrayEquals(bytes(0, 1000), s.snapshot())
    }

    @Test
    fun `填满后按时间正序倒出 —— 回绕点接错就是一段乱序音频`() {
        val s = sampler()
        s.feed(bytes(0, 6400))          // 正好填满
        assertArrayEquals(bytes(0, 6400), s.snapshot())
        s.feed(bytes(100, 1000))        // 回绕：挤掉最早的 1000 字节
        val got = s.snapshot()
        assertEquals(6400, got.size)
        // 期望 = 原数据的后 5400 字节 + 新的 1000 字节
        val want = bytes(0, 6400).copyOfRange(1000, 6400) + bytes(100, 1000)
        assertArrayEquals(want, got)
    }

    @Test
    fun `一直喂只保留最近的一窗 —— 环不能涨`() {
        val s = sampler()
        repeat(50) { s.feed(bytes(it, 3200)) }
        assertEquals("容量必须恒定", 6400, s.snapshot().size)
        assertEquals(200, s.bufferedMs())
    }

    @Test
    fun `单块超过环容量时只留最后一窗 —— 不能越界崩掉`() {
        val s = sampler()
        s.feed(bytes(0, 20000))
        val got = s.snapshot()
        assertEquals(6400, got.size)
        assertArrayEquals(bytes(0, 20000).copyOfRange(20000 - 6400, 20000), got)
    }

    @Test
    fun `空块不改变状态`() {
        val s = sampler()
        s.feed(bytes(0, 100))
        s.feed(ByteArray(0))
        assertEquals(100, s.snapshot().size)
    }

    @Test
    fun `clear 之后从头开始 —— 上一次唤醒的尾巴不能混进下一次样本`() {
        val s = sampler()
        s.feed(bytes(0, 5000))
        s.clear()
        assertEquals(0, s.snapshot().size)
        s.feed(bytes(7, 300))
        assertArrayEquals(bytes(7, 300), s.snapshot())
    }
}
