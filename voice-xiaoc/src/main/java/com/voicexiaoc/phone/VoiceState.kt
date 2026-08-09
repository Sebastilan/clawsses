package com.voicexiaoc.phone

/**
 * UI-facing state of the always-on voice pipeline, surfaced by
 * [VoiceXiaocService.voiceState] and rendered on the status screen.
 *
 * Flow (P3): WakeListening (mic always streaming, waiting for "小C") →
 * Listening (armed, waiting for the command sentence) → Recognizing(partial)
 * → Sent → Reply → WakeListening
 */
sealed class VoiceState {
    /** Not listening at all (ASR pipeline not started / creds missing). */
    object Idle : VoiceState()

    /** Continuous mic streaming, passively waiting to hear the wake word. */
    object WakeListening : VoiceState()

    /** Wake word heard — armed, waiting for the command sentence. */
    object Listening : VoiceState()

    /** ASR is returning text (interim + finalized sentences). */
    data class Recognizing(val text: String) : VoiceState()

    /** Final transcript sent to the gateway. */
    data class Sent(val text: String) : VoiceState()

    /** Reply received from the gateway (echo/text_reply), being spoken. */
    data class Reply(val text: String) : VoiceState()

    /** Something failed (permission / creds / network / ASR). */
    data class Error(val message: String) : VoiceState()

    /** Short human-readable label for the status screen. */
    fun label(): String = when (this) {
        is Idle -> "待机"
        is WakeListening -> "常听中（说 小C 唤醒）"
        is Listening -> "已唤醒，请说…"
        is Recognizing -> "识别中"
        is Sent -> "已发送"
        is Reply -> "收到回复"
        is Error -> "错误"
    }
}
