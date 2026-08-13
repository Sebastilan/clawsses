package com.voicexiaoc.phone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 连接世代号的判活语义 —— 钉住 2026-08-13 那个藏了很久的竞态。
 *
 * 原来的写法是 `if (ws !== activeWs) return`，而 `activeWs = newWs` 在
 * `client.newWebSocket(...)` **返回之后**才执行；`onOpen` 跑在 OkHttp 的 IO
 * 线程上，可以抢在那句赋值之前触发。那一瞬 activeWs 还是 null，守卫成立，
 * onOpen 整个被 return —— sendConnect / startPingLoop / flushOutbox 全都没跑。
 *
 * 症状极具迷惑性：**APP 用起来是好的**（能说话、能收到回复），因为 send() 用的是
 * webSocket 而不是 activeWs。坏掉的是那些"平时看不见"的东西：服务端不知道
 * 客户端版本和能力、心跳从未发出、掉线补发队列从未刷新。网关侧的铁证是
 * 从头到尾只收到过 asr_text 一种帧，没有 connect 也没有 ping。
 *
 * 这里不测 OkHttp，只测判活规则本身：**新连接一旦建立，旧回调必须失效；
 * 而当前世代的回调必须活着 —— 无论外部变量什么时候赋值。**
 */
class ConnGenerationTest {

    /** 复刻 WsClient 的世代判活逻辑。 */
    private class Gen {
        val counter = AtomicInteger(0)
        fun begin(): Int = counter.incrementAndGet()
        fun stale(myGen: Int): Boolean = myGen != counter.get()
    }

    @Test
    fun `回调抢在外部赋值之前触发也必须生效 —— 这正是老bug漏掉connect帧的原因`() {
        val g = Gen()
        val myGen = g.begin()
        // 模拟 onOpen 抢跑：此刻外面的 activeWs 还没赋值（相当于仍是 null）
        assertFalse("当前世代的回调不该被判为过期", g.stale(myGen))
    }

    @Test
    fun `建立新连接后旧回调必须失效 —— 否则旧socket会把状态改回已连接`() {
        val g = Gen()
        val oldGen = g.begin()
        g.begin()                       // 重连：世代 +1
        assertTrue("旧世代必须过期", g.stale(oldGen))
    }

    @Test
    fun `断开时作废世代 —— 断开后在途回调不能再翻盘`() {
        val g = Gen()
        val myGen = g.begin()
        g.begin()                       // disconnect() 里的 incrementAndGet
        assertTrue("断开后在途回调必须失效", g.stale(myGen))
    }

    @Test
    fun `连续重连时只有最新一代存活`() {
        val g = Gen()
        val gens = (1..5).map { g.begin() }
        gens.dropLast(1).forEach { assertTrue("第 $it 代应已过期", g.stale(it)) }
        assertFalse("最新一代应存活", g.stale(gens.last()))
    }
}
