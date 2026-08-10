package com.voicexiaoc.phone

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 唤醒 / 录命令 的状态机。整个语音链路里最容易出错的那部分，单独成文件。
 *
 * 拆出来的理由：这段逻辑此前散在 VoiceXiaocService 里，和服务生命周期、WebSocket
 * 接线、OTA、通知、崩溃上报挤在同一个 496 行的文件里（一文六职）。而它本身又是
 * 状态最多、竞态最密的一块——两条独立的数据流（回复文本 / 回复音频）各自去抢
 * 麦克风，多个定时器互相取消，靠一个布尔量记"谁在听"。结果就是：
 *   · 播报还没开始 0.8 秒，麦克风就自己开了，听见自己的 TTS 又转成新指令（自激）
 *   · 8 处 audio.stop() 不清标志，播报后系统对唤醒词变聋
 * 状态显式化之后，这两类 bug 在结构上不可表达。
 *
 * ── 状态图 ──────────────────────────────────────────────────────
 *
 *   IDLE ──start()──> WAKE_LISTENING
 *                     （麦克风归本地 KWS，音频不出手机）
 *                            │ 本地命中唤醒词 / 用户点按
 *                            ↓
 *                       ARMED（麦克风归云端 ASR，开始录这一句）
 *                            │
 *          ┌─────────────────┼──────────────────┐
 *     说了话且停顿         一直没开口          出错
 *     (ARM_SILENCE)      (ARM_TIMEOUT)          │
 *          ↓                 ↓                  ↓
 *      SPEAKING ────────> WAKE_LISTENING <──────┘
 *   （播报中，麦克风交还 NONE，
 *     半双工，绝不在说话时听）
 *          │ 播完
 *          ↓
 *   FOLLOW_UP（跟进窗口：不用再说唤醒词，超时回 WAKE_LISTENING）
 *
 * 铁律：**麦克风任一时刻只有一个归属**，且归属只能由 [onMic] 一处切换。
 */
class WakeMachine(
    private val scope: CoroutineScope,
    private val armTimeoutMs: Long,
    private val silenceSubmitMs: Long,
    /** 切换麦克风归属。实现方必须先停旧的再开新的。 */
    private val onMic: (MicOwner) -> Unit,
    /** 需要开一路云端 ASR 来录命令。 */
    private val onOpenAsr: () -> Unit,
    /** 关掉云端 ASR。 */
    private val onCloseAsr: () -> Unit,
    /** 一句完整命令收齐，交出去。 */
    private val onCommand: (String) -> Unit,
    /** 状态变化，供 UI/日志观察。 */
    private val onState: (State) -> Unit,
) {
    enum class MicOwner { NONE, KWS, ASR }

    enum class State { IDLE, WAKE_LISTENING, ARMED, SPEAKING, FOLLOW_UP }

    var state: State = State.IDLE
        private set

    private val heard = StringBuilder()
    private var armTimer: Job? = null
    private var silenceTimer: Job? = null

    private fun to(next: State) {
        state = next
        onState(next)
    }

    private fun cancelTimers() {
        armTimer?.cancel(); armTimer = null
        silenceTimer?.cancel(); silenceTimer = null
    }

    /** 服务启动：进入常听（本地）。 */
    fun start() {
        if (state != State.IDLE) return
        toWakeListening()
    }

    fun stop() {
        cancelTimers()
        onCloseAsr()
        onMic(MicOwner.NONE)
        to(State.IDLE)
    }

    /** 回到「等唤醒词」——麦克风交回本地 KWS，云端关闭。所有收尾都走这一条路。 */
    fun toWakeListening() {
        cancelTimers()
        heard.setLength(0)
        onCloseAsr()
        onMic(MicOwner.KWS)
        to(State.WAKE_LISTENING)
    }

    /** 本地 KWS 命中，或用户手动点按。 */
    fun onWake() {
        if (isArmed) return
        arm()
    }

    /**
     * 进入录命令状态：麦克风交给云端 ASR，起「一直没开口」超时。
     *
     * [followUp] 只影响状态命名（给 UI/日志区分「刚被唤醒」和「答完话的跟进窗口」），
     * 行为完全一致——两者都是在等用户开口，超时都回常听。
     */
    private fun arm(followUp: Boolean = false) {
        cancelTimers()
        heard.setLength(0)
        onOpenAsr()
        onMic(MicOwner.ASR)
        to(if (followUp) State.FOLLOW_UP else State.ARMED)
        armTimer = scope.launch {
            delay(armTimeoutMs)
            if (isArmed && heard.isEmpty()) toWakeListening()
        }
    }

    /** ARMED 与 FOLLOW_UP 在行为上是同一件事：麦克风归 ASR，正在等/收用户的话。 */
    private val isArmed: Boolean
        get() = state == State.ARMED || state == State.FOLLOW_UP

    /** ASR 吐出一段文本（中间态或最终态）。每次都把「说完了」的判定往后推。 */
    fun onHeard(text: String, isFinal: Boolean) {
        if (!isArmed) return
        if (isFinal) heard.append(text)
        if (text.isBlank()) return
        armTimer?.cancel(); armTimer = null   // 已经开口了，「没开口」超时不再适用
        silenceTimer?.cancel()
        silenceTimer = scope.launch {
            delay(silenceSubmitMs)
            submit()
        }
    }

    /** 用户停顿够久（或手动点按提前提交）：把攒下的话交出去。 */
    fun submit() {
        if (!isArmed) return
        cancelTimers()
        val text = heard.toString().trim().takeUnless { isJunk(it) } ?: ""
        heard.setLength(0)
        onCloseAsr()
        // 交出去之后立刻闭麦：等待 CC 回复期间没有任何理由继续听。
        onMic(MicOwner.NONE)
        if (text.isEmpty()) {
            toWakeListening()
        } else {
            to(State.SPEAKING)
            onCommand(text)
        }
    }

    /**
     * 开始播报。半双工：整段播报期间麦克风必须是 NONE。
     *
     * 之前的自激循环就出在这里——回复文本和回复音频是两条独立的流，文本一到就
     * 起了个 800ms 定时器去重开麦，而那时音频才刚开始念。现在播报期间的状态是
     * 显式的 SPEAKING，[onHeard] 在这个状态下直接忽略，重开麦只能由 [onSpoken]
     * 触发，物理上不可能在说话时听。
     */
    fun onSpeaking() {
        cancelTimers()
        onMic(MicOwner.NONE)
        to(State.SPEAKING)
    }

    /** 播报结束：给一个不用再说唤醒词的跟进窗口，超时自动回常听。 */
    fun onSpoken() {
        if (state != State.SPEAKING) return
        arm(followUp = true)
    }

    /**
     * 纯标点/极短碎片不算内容。ASR 常把唤醒词那一声单独出一个 final（"。"），
     * 若当成命令提交，会在用户真正开口前就把 ARMED 窗口关掉，表现为"喊了没反应"。
     * 网关侧也有一道同样的过滤，但那是兜底——该在源头拦住，别浪费一次云端往返。
     */
    private fun isJunk(text: String): Boolean =
        text.replace(Regex("[，,。.!?！？…\\s]"), "").length < 2

    /** 链路出错：不管在哪个状态，一律安全地退回常听。 */
    fun onError() = toWakeListening()
}
