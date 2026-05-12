package com.superbrain.glasses

import androidx.compose.ui.graphics.Color


/**
 * HUD state for SuperBrain glasses display.
 */
data class HudState(
    val messages: List<HudMessage> = emptyList(),
    val isConnected: Boolean = false,
    val isListening: Boolean = false,
    val isStreaming: Boolean = false,
    val statusText: String = "SuperBrain",
    val streamingText: String = "",
    val asrText: String = "",
    val asrIsFinal: Boolean = false,
    // Observer (旁听) mode
    val observerMode: Boolean = false
)

data class HudMessage(
    val role: String,  // "user", "assistant", "system"
    val content: String,
    val isStreaming: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Color palette for monochrome green HUD (480x640 micro-LED).
 */
object HudColors {
    val green = Color(0xFF00FF00)
    val dimGreen = Color(0xFF006600)
    val darkGreen = Color(0xFF003300)
    val background = Color.Black
    val cyan = Color(0xFF00FFFF)
    val yellow = Color(0xFFFFFF00)
    val error = Color(0xFFFF3333)
}
