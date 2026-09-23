package com.dialex.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.audio.PlatformSpeechRecognizer
import com.dialex.audio.SpeechRecognitionState
import com.dialex.audio.SpeechTextMerger
import com.dialex.audio.rememberPlatformSpeechRecognizer
import com.dialex.theme.LocalCcColors

/**
 * Reusable Voice Input Button for Big Text Areas.
 * Provides:
 * - Live microphone listening state with audio amplitude pulsation
 * - Partial transcription preview floating badge
 * - Smart text appending into target string state
 */
@Composable
fun VoiceInputButton(
    currentText: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 26.dp,
    iconSize: Dp = 15.dp,
    language: String = "en-US",
    recognizer: PlatformSpeechRecognizer = rememberPlatformSpeechRecognizer(),
    onSpeechResult: ((String) -> Unit)? = null
) {
    val cc = LocalCcColors.current
    val speechState by recognizer.state.collectAsState()

    val isListening = speechState is SpeechRecognitionState.Listening || speechState is SpeechRecognitionState.Initializing
    val rmsLevel = (speechState as? SpeechRecognitionState.Listening)?.rmsDb ?: 0f

    // React to final speech results
    LaunchedEffect(speechState) {
        val state = speechState
        if (state is SpeechRecognitionState.Result && state.text.isNotBlank()) {
            val updated = SpeechTextMerger.merge(currentText, state.text)
            onTextChange(updated)
            onSpeechResult?.invoke(state.text)
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val basePulse by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "basePulse"
    )

    val activeScale = if (isListening) {
        (basePulse + (rmsLevel * 0.35f)).coerceIn(1.0f, 1.6f)
    } else 1.0f

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        ThemedTooltipBox(if (isListening) "Listening... Click to finish dictation" else "Dictate with voice") {
            Box(
                modifier = Modifier
                    .size(size)
                    .scale(activeScale)
                    .clip(CircleShape)
                    .background(
                        if (isListening) cc.accent.copy(alpha = 0.25f)
                        else Color.Transparent
                    )
                    .border(
                        width = if (isListening) 1.5.dp else 0.dp,
                        color = if (isListening) cc.accent else Color.Transparent,
                        shape = CircleShape
                    )
                    .clickable {
                        if (isListening) {
                            recognizer.stopListening()
                        } else {
                            recognizer.startListening(language)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isListening) Icons.Default.Stop else Icons.Outlined.Mic,
                    contentDescription = if (isListening) "Stop dictation" else "Start dictation",
                    tint = if (isListening) cc.accent else cc.textMuted,
                    modifier = Modifier.size(iconSize)
                )
            }
        }
    }
}

/**
 * Floating or inline live transcription indicator banner showing partial speech text.
 */
@Composable
fun LiveSpeechTranscriptBadge(
    speechState: SpeechRecognitionState,
    modifier: Modifier = Modifier
) {
    val cc = LocalCcColors.current
    val isListening = speechState is SpeechRecognitionState.Listening
    val partialText = (speechState as? SpeechRecognitionState.Listening)?.partialText.orEmpty()

    AnimatedVisibility(
        visible = isListening,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .background(cc.accent.copy(alpha = 0.12f))
                .border(1.dp, cc.accent.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(cc.accent)
            )
            Text(
                text = if (partialText.isNotBlank()) "“$partialText”" else "Listening...",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                color = cc.accent
            )
        }
    }
}
