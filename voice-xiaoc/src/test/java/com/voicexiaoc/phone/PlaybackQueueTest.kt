package com.voicexiaoc.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 每个测试名都是一个"如果写错了，统帅会遇到什么"。
 * 照 WakeMachineTest 的路子：不测 getter/setter，只测真会咬人的地方。
 */
class PlaybackQueueTest {

    @Test
    fun `队列空但没收到末段时不能判定说完 —— 否则播报中途重开麦，又是自激循环`() {
        val q = PlaybackQueue<String>()
        q.enqueue("第一句", isFinal = false)
        assertEquals("第一句", q.next())
        // 第二段还在路上，此刻队列是空的
        assertNull(q.next())
        assertFalse("队列空≠说完了", q.isRoundDone())
    }

    @Test
    fun `末段播完才算说完 —— 这时才该重开麦`() {
        val q = PlaybackQueue<String>()
        q.enqueue("第一句", isFinal = false)
        q.enqueue("最后一句", isFinal = true)
        assertEquals("第一句", q.next())
        assertFalse(q.isRoundDone())
        assertEquals("最后一句", q.next())
        assertNull(q.next())
        assertTrue(q.isRoundDone())
    }

    @Test
    fun `整段模式一段带final —— 老网关配新APK必须和从前一样收尾`() {
        val q = PlaybackQueue<String>()
        assertTrue("空闲时入队应点火", q.enqueue("整段回复", isFinal = true))
        assertEquals("整段回复", q.next())
        assertNull(q.next())
        assertTrue(q.isRoundDone())
    }

    @Test
    fun `已在播时入队不重复点火 —— 否则两个播放器同时出声`() {
        val q = PlaybackQueue<String>()
        assertTrue(q.enqueue("A", isFinal = false))
        assertFalse("已经在播了", q.enqueue("B", isFinal = false))
        assertFalse(q.enqueue("C", isFinal = true))
    }

    @Test
    fun `打断要清空整个队列 —— 只停当前段的话它会顿一下继续自说自话`() {
        val q = PlaybackQueue<String>()
        q.enqueue("A", isFinal = false)
        q.enqueue("B", isFinal = false)
        q.enqueue("C", isFinal = true)
        q.next()  // A 正在播
        assertEquals("应丢弃 B 和 C", 2, q.clear())
        assertNull(q.next())
        assertFalse("打断不是说完，不该触发收尾回调", q.isRoundDone())
    }

    @Test
    fun `打断后能立刻开始新一轮 —— 打断的目的就是马上说下一件事`() {
        val q = PlaybackQueue<String>()
        q.enqueue("旧的", isFinal = false)
        q.clear()
        assertTrue("清空后必须能重新点火", q.enqueue("新的", isFinal = true))
        assertEquals("新的", q.next())
        assertTrue(q.isRoundDone())
    }

    @Test
    fun `末段先于中间段到达也不会提前收尾 —— 以队列排空为准而非以收到final为准`() {
        val q = PlaybackQueue<String>()
        q.enqueue("A", isFinal = false)
        q.enqueue("B", isFinal = true)
        assertFalse("B 还没播，不算说完", q.isRoundDone())
        q.next()
        assertFalse(q.isRoundDone())
        q.next()
        assertTrue(q.isRoundDone())
    }
}
