package com.voicexiaoc.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 结束词识别 —— "说完了就立刻发"的那条快车道。
 *
 * 计时器只能猜他说完没有，结束词是他**亲口说的**，所以更准。但只能当快车道：
 * 眼镜项目那边验证过同一套设计，留下的教训是「已实现但用户实测时未必每句都用」
 * （super-brain/memex/modules/asr-pipeline.md），所以静音兜底必须还在。
 *
 * 这里钉住的都是"判错了会咬人"的地方：
 *   · 句中的"好了/就这样"当成结束 → 把他的话拦腰截断，而他根本没说完
 *   · 结束词没剥掉就发出去 → 小C 收到"...完毕"，会当成话的内容去理解
 */
class EndWordTest {

    private fun isEnd(s: String) = WakeMachine.endWordIndex(s) >= 0

    @Test
    fun `句尾说结束词 → 认`() {
        assertTrue(isEnd("帮我记一下明天开会 完毕"))
        assertTrue(isEnd("查一下天气，就这样"))
        assertTrue(isEnd("那就这么定了。说完了"))
        assertTrue(isEnd("给我讲讲郑州 over"))
        assertTrue(isEnd("这事儿先放着，就这些。"))   // 后面带标点也算
    }

    @Test
    fun `句中出现不算 —— 否则会把他的话拦腰截断`() {
        assertTrue("「就这样」在句中", !isEnd("就这样吧我再想想"))
        assertTrue("「完毕」在句中", !isEnd("完毕之后你告诉我"))
        assertTrue("「结束」在句中", !isEnd("结束以后我们再聊"))
    }

    @Test
    fun `光一个结束词不算命令 —— 多半是误识别`() {
        assertTrue(!isEnd("完毕"))
        assertTrue(!isEnd("over"))
        assertTrue(!isEnd("就这样。"))
    }

    @Test
    fun `发出去之前必须剥掉结束词 —— 那是控制信号不是话`() {
        assertEquals("帮我记一下明天开会", WakeMachine.stripEndWord("帮我记一下明天开会 完毕"))
        assertEquals("查一下天气", WakeMachine.stripEndWord("查一下天气，就这样"))
        assertEquals("给我讲讲郑州", WakeMachine.stripEndWord("给我讲讲郑州 over"))
    }

    @Test
    fun `没有结束词的原样不动 —— 不能顺手改他的话`() {
        assertEquals("查一下天气", WakeMachine.stripEndWord("查一下天气"))
        assertEquals("就这样吧我再想想", WakeMachine.stripEndWord("就这样吧我再想想"))
    }

    @Test
    fun `大小写不敏感 —— ASR 出 OVER 还是 over 都认`() {
        assertTrue(isEnd("查一下天气 OVER"))
        assertTrue(isEnd("查一下天气 Over"))
    }
}
