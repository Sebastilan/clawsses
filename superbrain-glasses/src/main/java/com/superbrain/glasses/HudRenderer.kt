package com.superbrain.glasses

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

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
    // Wake word / enrollment state
    val wakeWordActive: Boolean = false,
    val modelsLoaded: Boolean = false,
    val enrolling: Boolean = false,
    val enrollProgress: Int = 0,
    val enrollNeeded: Int = 3,
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

val JetBrainsMono = FontFamily(Font(R.font.jetbrains_mono))

/**
 * Main HUD composable for the 480x640 green monochrome display.
 * Minimal design: black screen with ephemeral messages (5s TTL) and a bottom status line.
 */
@Composable
fun HudScreen(hudState: StateFlow<HudState>) {
    val state by hudState.collectAsState()
    val listState = rememberLazyListState()

    // Tick every second to expire old messages
    val currentTime by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1000)
            value = System.currentTimeMillis()
        }
    }

    // Streaming messages always visible; completed messages live 5s
    val visibleMessages = state.messages.filter {
        it.isStreaming || (currentTime - it.timestamp < 5000L)
    }

    LaunchedEffect(visibleMessages.size, state.streamingText) {
        if (visibleMessages.isNotEmpty()) {
            listState.animateScrollToItem(visibleMessages.lastIndex)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HudColors.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
        ) {
            // ── Chat Messages (only when present) ──
            if (visibleMessages.isNotEmpty()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(visibleMessages, key = { idx, _ -> idx }) { _, msg ->
                        ChatBubble(msg)
                    }
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            // ── ASR Subtitle ──
            if (state.isListening || state.asrText.isNotBlank()) {
                AsrSubtitle(
                    text = state.asrText,
                    isFinal = state.asrIsFinal,
                    isListening = state.isListening
                )
            }

            // ── Enrollment overlay ──
            if (state.enrolling) {
                EnrollmentBar(progress = state.enrollProgress, needed = state.enrollNeeded)
            }

            // ── Minimal bottom status line ──
            MinimalStatusLine(state)
        }
    }
}

@Composable
private fun MinimalStatusLine(state: HudState) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            state.observerMode ->
                PulsingText("旁听中 | 说退出旁听", HudColors.dimGreen, 10.sp)
            state.wakeWordActive && !state.isListening ->
                Text(
                    text = "说小C唤醒",
                    style = TextStyle(fontFamily = JetBrainsMono, fontSize = 10.sp, color = HudColors.dimGreen)
                )
            // Listening/streaming: ASR subtitle handles status display
        }
    }
}

@Composable
private fun ChatBubble(msg: HudMessage) {
    val isUser = msg.role == "user"
    val isSystem = msg.role == "system"

    val textColor = when {
        isSystem -> HudColors.cyan
        isUser -> HudColors.yellow
        else -> HudColors.green
    }
    val alpha = if (isSystem) 0.7f else 1f
    val align = if (isUser) TextAlign.End else TextAlign.Start

    val displayText = if (msg.isStreaming) {
        msg.content + "\u2588"  // Block cursor
    } else {
        msg.content
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha)
    ) {
        if (!isSystem) {
            Text(
                text = if (isUser) "You" else "AI",
                style = TextStyle(
                    fontFamily = JetBrainsMono,
                    fontSize = 9.sp,
                    color = textColor.copy(alpha = 0.5f)
                ),
                textAlign = align,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Text(
            text = displayText,
            style = TextStyle(
                fontFamily = JetBrainsMono,
                fontSize = 12.sp,
                color = textColor,
                lineHeight = 16.sp
            ),
            textAlign = align,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun EnrollmentBar(progress: Int, needed: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(HudColors.background)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Enroll: say '\u5C0FC' ($progress/$needed)",
            style = TextStyle(
                fontFamily = JetBrainsMono,
                fontSize = 12.sp,
                color = HudColors.yellow,
            )
        )
    }
}

@Composable
private fun AsrSubtitle(text: String, isFinal: Boolean, isListening: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(HudColors.background)
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        if (text.isBlank() && isListening) {
            // Pulsing "Listening..." indicator
            PulsingText("\uD83C\uDF99 Listening...", HudColors.yellow, 11.sp)
        } else if (text.isNotBlank()) {
            val displayText = if (!isFinal) "$text\u2588" else text  // Block cursor for interim
            val textColor = if (isFinal) HudColors.yellow else HudColors.yellow.copy(alpha = 0.8f)
            Text(
                text = displayText,
                style = TextStyle(
                    fontFamily = JetBrainsMono,
                    fontSize = 12.sp,
                    color = textColor,
                    lineHeight = 16.sp
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun PulsingText(text: String, color: Color, fontSize: androidx.compose.ui.unit.TextUnit) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600)),
        label = "pulseAlpha"
    )
    Text(
        text = text,
        style = TextStyle(fontSize = fontSize, color = color),
        modifier = Modifier.alpha(alpha)
    )
}
