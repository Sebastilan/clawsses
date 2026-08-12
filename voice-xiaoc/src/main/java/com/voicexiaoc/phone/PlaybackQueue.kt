package com.voicexiaoc.phone

/**
 * 分句播报的排队逻辑 —— 只管"下一个放谁、这轮完没完"，不碰 MediaPlayer。
 *
 * 单独拎出来是因为这里有一条很容易写错、错了又很难发现的不变式：
 *
 *     **队列空了不等于说完了。**
 *
 * 网关把长回复切成句子边合成边下发，网络慢于播放是常态：播完第 2 段时第 3 段
 * 可能还在路上，队列此刻是空的。若把"空"当成"说完了"就会提前重开麦，接着第 3 段
 * 到货又开始播 —— 麦克风听见小C自己的声音，转写成新指令再问一遍，正是之前那个
 * 自激循环。只有**播完带 final 标记的那一段**才算说完。
 *
 * 反过来也有个坑：整段模式（网关没开分句流式）下一轮只有一段、且带 final，
 * 行为必须和从前一模一样，否则老网关配新 APK 就永远等不到收尾。
 *
 * 这个类是纯 Kotlin，不 import 任何 android.* —— 所以能在 JVM 上直接跑单测
 * （见 PlaybackQueueTest），不用真机、不用模拟器。同 WakeMachine 的路子。
 */
class PlaybackQueue<T> {

    private val items = ArrayDeque<T>()

    /** 是否已经收到本轮的末段。收到之前，队列见底也只是"等下一段"。 */
    var sawFinal = false
        private set

    /** 是否正在播（含"播完了在等下一段"）。用来判断 enqueue 要不要点火。 */
    var active = false
        private set

    val size: Int get() = items.size

    /**
     * 排入一段。返回 true 表示调用方**现在就该开播**（此前是空闲的）；
     * 返回 false 表示已经在播了，这段乖乖排队。
     */
    fun enqueue(item: T, isFinal: Boolean): Boolean {
        if (isFinal) sawFinal = true
        items.addLast(item)
        if (active) return false
        active = true
        return true
    }

    /** 取下一段。返回 null 时用 [isRoundDone] 区分"说完了"还是"等下一段"。 */
    fun next(): T? {
        val item = items.removeFirstOrNull()
        if (item == null && sawFinal) active = false
        return item
    }

    /**
     * [next] 返回 null 之后问这一句：true = 本轮真的说完了（该重开麦），
     * false = 只是下一段还没到（保持播报状态，别动麦克风）。
     */
    fun isRoundDone(): Boolean = items.isEmpty() && sawFinal

    /** 打断：清空一切，返回被丢弃的段数（供日志）。 */
    fun clear(): Int {
        val dropped = items.size
        items.clear()
        sawFinal = false
        active = false
        return dropped
    }
}
