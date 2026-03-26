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
    val enrollNeeded: Int = 3
)

data class HudMessage(
    val role: String,  // "user", "assistant", "system"
    val content: String,
    val isStreaming: Boolean = false
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
 */
@Composable
fun HudScreen(hudState: StateFlow<HudState>) {
    val state by hudState.collectAsState()
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new messages arrive
    val messageCount = state.messages.size
    val isStreaming = state.isStreaming
    LaunchedEffect(messageCount, state.streamingText) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HudColors.background)
            .padding(4.dp)
    ) {
        // ── Status Bar ──
        StatusBar(
            isConnected = state.isConnected,
            isListening = state.isListening,
            statusText = state.statusText
        )

        Spacer(modifier = Modifier.height(2.dp))

        // ── Divider ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(HudColors.dimGreen)
        )

        // ── Chat Messages ──
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(state.messages, key = { idx, _ -> idx }) { _, msg ->
                ChatBubble(msg)
            }
        }

        // ── ASR Subtitle ──
        if (state.isListening || state.asrText.isNotBlank()) {
            AsrSubtitle(
                text = state.asrText,
                isFinal = state.asrIsFinal,
                isListening = state.isListening
            )
        }

        // ── Bottom Divider ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(HudColors.dimGreen)
        )

        Spacer(modifier = Modifier.height(2.dp))

        // ── Enrollment overlay ──
        if (state.enrolling) {
            EnrollmentBar(progress = state.enrollProgress, needed = state.enrollNeeded)
        }

        // ── Bottom Status Bar ──
        BottomBar(
            isListening = state.isListening,
            isStreaming = state.isStreaming,
            wakeWordActive = state.wakeWordActive
        )
    }
}

@Composable
private fun StatusBar(isConnected: Boolean, isListening: Boolean, statusText: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = statusText,
            style = TextStyle(
                fontFamily = JetBrainsMono,
                fontSize = 11.sp,
                color = HudColors.green
            ),
            modifier = Modifier.weight(1f)
        )

        // Connection indicator
        val dotColor = if (isConnected) HudColors.green else HudColors.error
        val dotText = if (isConnected) "\u25CF" else "\u25CB"  // ● or ○
        Text(
            text = dotText,
            style = TextStyle(fontSize = 12.sp, color = dotColor)
        )

        if (isListening) {
            Spacer(modifier = Modifier.width(4.dp))
            PulsingText("\uD83C\uDF99", HudColors.yellow, 11.sp)  // 🎙
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
private fun BottomBar(isListening: Boolean, isStreaming: Boolean, wakeWordActive: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (wakeWordActive && !isListening) {
            // Wake word mode: show "say 小C"
            PulsingText("say \u5C0FC", HudColors.dimGreen, 10.sp)
        } else {
            val micColor = if (isListening) HudColors.yellow else HudColors.dimGreen
            Text(
                text = "\uD83C\uDF99 Listen",
                style = TextStyle(fontFamily = JetBrainsMono, fontSize = 10.sp, color = micColor)
            )
        }

        Text(
            text = "\uD83D\uDCF7 Photo",
            style = TextStyle(fontFamily = JetBrainsMono, fontSize = 10.sp, color = HudColors.dimGreen)
        )

        val streamColor = if (isStreaming) HudColors.cyan else HudColors.dimGreen
        val streamIcon = if (isStreaming) "\u26A1" else "\u2022"  // ⚡ or •
        Text(
            text = streamIcon,
            style = TextStyle(fontFamily = JetBrainsMono, fontSize = 10.sp, color = streamColor)
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
