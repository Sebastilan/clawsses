package com.voicexiaoc.phone

/**
 * UI-facing state of the push-to-talk voice pipeline, surfaced by
 * [VoiceXiaocService.voiceState] and rendered on the status screen.
 *
 * Flow: Idle → Listening → Recognizing(partial) → Sent → Reply → Idle
 */
sealed class VoiceState {
    /** Not listening. */
    object Idle : VoiceState()

    /** Mic open, connecting/streaming to ASR, no transcript yet. */
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
        is Listening -> "正在录音…"
        is Recognizing -> "识别中"
        is Sent -> "已发送"
        is Reply -> "收到回复"
        is Error -> "错误"
    }
}
