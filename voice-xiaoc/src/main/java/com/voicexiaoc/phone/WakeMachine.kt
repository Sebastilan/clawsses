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
 *   （播报中，麦克风归本地 KWS：
 *     只听那四个字，云端 ASR 关闭。
 *     听到 → 掐断播报，直接进 ARMED＝打断）
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
    /** 打断：用户在播报期间喊了唤醒词，掐掉正在播的音频。 */
    private val onStopSpeaking: () -> Unit,
    /** 状态变化，供 UI/日志观察。 */
    private val onState: (State) -> Unit,
    /** 关键决策的诊断说明（为什么发/为什么不发），回传网关日志。 */
    private val onDiag: (String) -> Unit = {},
) {
    companion object {
        /**
         * 该被当成"我们自己的回声"扔掉的短句。
         *
         * 唤醒应答那声"我在"是我们自己放的，而它响的时候麦克风已经开着；唤醒词
         * 本身也常被 ASR 单独出一个 final。这两种都不是他要说的话，当成命令发出去
         * 就会凭空多一轮对话（他还没开口，小C 已经在回答"我在"了）。
         */
        private val ECHO_WORDS = setOf("我在", "健康顺利", "顺利健康")

        /**
         * 句末结束词 —— 说了就立刻提交，不再等静音。
         *
         * 这是"我说完了"的**显式信号**，比任何计时器都准：计时器只能猜，
         * 而这是他亲口说的。之前在眼镜项目上验证过（super-brain 的
         * asr-pipeline.md，2026-04-25），但那边留了条实测教训：
         * **「已实现但用户实测时未必每句都用」** —— 所以它只是快车道，
         * 不能当唯一机制，静音兜底仍然必须在。
         *
         * 只认**句尾**出现（见 [endWordIndex]）：句中说到"好了"很常见
         * （"好了没有""这事好了之后"），当成结束会把他的话拦腰截断。
         */
        private val END_WORDS = listOf(
            "over", "完毕", "说完了", "我说完了", "就这样", "就这些", "结束",
        )

        /**
         * 结束词在句尾的起始下标；没有返回 -1。
         *
         * **只看尾巴**：允许后面跟标点/空白，但不许再有别的字。
         * "就这样吧我再想想" 里的"就这样"不算 —— 他还在说。
         */
        fun endWordIndex(text: String): Int {
            val t = text.trimEnd { it.isWhitespace() || it in "。，,.!?！？…、" }
            for (w in END_WORDS) {
                if (t.endsWith(w, ignoreCase = true)) {
                    // 光一个结束词、前面什么都没说，不算命令（多半是误识别）
                    val head = t.dropLast(w.length).trim { it.isWhitespace() || it in "。，,.!?！？…、" }
                    if (head.isEmpty()) return -1
                    return t.length - w.length
                }
            }
            return -1
        }

        /** 去掉句尾的结束词——那是控制信号，不该当成话的内容发给小C。 */
        fun stripEndWord(text: String): String {
            val i = endWordIndex(text)
            if (i < 0) return text
            val t = text.trimEnd { it.isWhitespace() || it in "。，,.!?！？…、" }
            return t.substring(0, i).trim { it.isWhitespace() || it in "。，,.!?！？…、" }
        }
    }

    enum class MicOwner { NONE, KWS, ASR }

    enum class State { IDLE, WAKE_LISTENING, ARMED, SPEAKING, FOLLOW_UP }

    var state: State = State.IDLE
        private set

    private val heard = StringBuilder()

    /**
     * 最近一次识别中间结果。腾讯的 final(slice_type=2) 依赖它自己的断句判定，
     * 实测存在"partial 一直来、final 始终不来"的情况(2026-08-10 首次唤醒即遇到:
     * ASR 明明听见了、状态机等到超时也没拿到一个字)。只认 final 就会整句丢掉,
     * 所以提交时把最后一段 partial 折进来兜底。
     */
    private var lastPartial = ""

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
        lastPartial = ""
        onCloseAsr()
        onMic(MicOwner.KWS)
        to(State.WAKE_LISTENING)
    }

    /**
     * 本地 KWS 命中，或用户手动点按。
     *
     * 在 SPEAKING 状态下命中 = **打断**：小C 还在说话，你插话把它掐了。
     * 这是"随时能打断"这个体感的来源。之所以只认唤醒词而不是任意人声：
     * 手机的麦和喇叭在同一台设备上，播报时开麦必然听见自己，而 Android 的
     * 硬件 AEC 在媒体音频路(A2DP)上拿不到参考信号、等于没开。用只认四个字的
     * 本地 KWS 当触发器，既躲开自激，又不必把音频送上云端。
     */
    fun onWake() {
        if (isArmed) return
        if (state == State.SPEAKING) onStopSpeaking()
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
            if (isArmed && heard.isEmpty() && lastPartial.isEmpty()) {
                onDiag("arm 超时 ${armTimeoutMs}ms 内一个字都没听到，回常听")
                toWakeListening()
            }
        }
    }

    /** ARMED 与 FOLLOW_UP 在行为上是同一件事：麦克风归 ASR，正在等/收用户的话。 */
    private val isArmed: Boolean
        get() = state == State.ARMED || state == State.FOLLOW_UP

    /** ASR 吐出一段文本（中间态或最终态）。每次都把「说完了」的判定往后推。 */
    fun onHeard(text: String, isFinal: Boolean) {
        if (!isArmed) return
        if (isFinal) {
            heard.append(text)
            lastPartial = ""       // 这段已经定稿，不再需要兜底
        } else {
            lastPartial = text
        }
        if (text.isBlank()) return
        armTimer?.cancel(); armTimer = null   // 已经开口了，「没开口」超时不再适用
        // 说了结束词就立刻发，不再等静音 —— 这是"我说完了"的显式信号，
        // 比任何计时器都准。见 [stripEndWord]。
        if (endWordIndex(heard.toString() + lastPartial) >= 0) {
            onDiag("听到结束词，立即提交（不等静音）")
            submit()
            return
        }
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
        // 结束词是控制信号,不是话的内容 —— 带着"完毕"发过去,小C 会当成他说的话
        // 去理解("完毕"是什么意思?),甚至跟着学舌。
        val raw = stripEndWord((heard.toString() + lastPartial).trim())
        val text = raw.takeUnless { isJunk(it) } ?: ""
        onDiag("submit: final=${heard.length}字 partial兜底=${lastPartial.length}字 -> " +
               if (text.isEmpty()) "空，不发" else "${text.length}字，发出")
        heard.setLength(0)
        lastPartial = ""
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
     * 开始播报。麦克风交给本地 KWS —— 在听，但**只听那四个字**，不连云端。
     *
     * 关键在于"听什么"而不是"听不听"：
     *   · 云端 ASR 在播报期间必须关着，否则它会把小C自己的声音转成文本再发回
     *     去问小C，一轮套一轮（这就是之前那个自激循环）。
     *   · 本地 KWS 只输出"是/不是健康顺利"，物理上产不出可回灌的文本，
     *     所以开着是安全的。
     *
     * [allowBargeIn]=false 用于回复文本本身含唤醒词的场合（如小C说"祝你健康
     * 顺利"），此时闭麦，免得它把自己打断。
     */
    fun onSpeaking(allowBargeIn: Boolean = true) {
        cancelTimers()
        onMic(if (allowBargeIn) MicOwner.KWS else MicOwner.NONE)
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
    private fun isJunk(text: String): Boolean {
        val bare = text.replace(Regex("[，,。.!?！？…\\s]"), "")
        if (bare.length < 2) return true
        // 唤醒应答("我在")是**我们自己放出去的声音**，而它响的时候麦克风已经开了
        // （故意的：等它放完再开麦，他一张嘴第一个字就丢了）。所以 ASR 必然听得见，
        // 得在这儿把它认出来扔掉，否则那声"我在"会被当成他的第一句命令发上云。
        // 唤醒词本身同理：ASR 常把那一声单独出一个 final。
        if (bare in ECHO_WORDS) return true
        return false
    }

    /** 链路出错：不管在哪个状态，一律安全地退回常听。 */
    fun onError() = toWakeListening()
}
