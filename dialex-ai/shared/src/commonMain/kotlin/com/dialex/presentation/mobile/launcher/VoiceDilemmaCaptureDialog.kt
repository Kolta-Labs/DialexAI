package com.dialex.presentation.mobile.launcher

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.dialex.domain.model.CouncilPreset
import com.dialex.domain.model.DilemmaDraft
import com.dialex.domain.usecase.ParseSpokenDilemmaUseCase
import com.dialex.theme.LocalCcColors

@Composable
fun VoiceDilemmaCaptureDialog(
    onDismiss: () -> Unit,
    onLaunchCouncil: (draft: DilemmaDraft, preset: CouncilPreset) -> Unit
) {
    val cc = LocalCcColors.current
    var spokenInput by remember { mutableStateOf("") }
    val parseUseCase = remember { ParseSpokenDilemmaUseCase() }
    val draft = remember(spokenInput) { parseUseCase(spokenInput) }

    val recognizer = com.dialex.audio.rememberPlatformSpeechRecognizer()
    val speechState by recognizer.state.collectAsState()
    val isListening = speechState is com.dialex.audio.SpeechRecognitionState.Listening || speechState is com.dialex.audio.SpeechRecognitionState.Initializing
    val rmsLevel = (speechState as? com.dialex.audio.SpeechRecognitionState.Listening)?.rmsDb ?: 0f

    // React to live speech results
    LaunchedEffect(speechState) {
        val state = speechState
        if (state is com.dialex.audio.SpeechRecognitionState.Result && state.text.isNotBlank()) {
            spokenInput = com.dialex.audio.SpeechTextMerger.merge(spokenInput, state.text)
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "micPulse")
    val idlePulse by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "micScale"
    )

    val activeMicScale = if (isListening) (1.0f + rmsLevel * 0.45f).coerceIn(1.05f, 1.6f) else idlePulse

    val matchedPreset = CouncilPreset.defaultPresets.firstOrNull { it.id == draft.suggestedPresetId }
        ?: CouncilPreset.ExecutiveRedTeam

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(cc.panel)
                .border(1.5.dp, cc.accent, RoundedCornerShape(18.dp))
                .padding(20.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Speak Your Dilemma",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = cc.textPrimary
                )
                Text(
                    text = if (isListening) "Listening... Speak your topic, budget, constraints, or goals." else "Tap the microphone below to start dictating or speak freely.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isListening) cc.accent else cc.textMuted
                )

                // Large Glowing Interactive Microphone Orb
                Box(
                    modifier = Modifier
                        .scale(activeMicScale)
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(
                            if (isListening) cc.accent.copy(alpha = 0.35f)
                            else cc.accent.copy(alpha = 0.15f)
                        )
                        .border(2.5.dp, cc.accent, CircleShape)
                        .clickable {
                            if (isListening) {
                                recognizer.stopListening()
                            } else {
                                recognizer.startListening()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.Default.Mic else Icons.Default.Mic,
                        contentDescription = if (isListening) "Listening - Tap to stop" else "Tap to speak",
                        tint = cc.accent,
                        modifier = Modifier.size(34.dp)
                    )
                }

                // Live Transcription Indicator
                com.dialex.ui.LiveSpeechTranscriptBadge(speechState = speechState)

                OutlinedTextField(
                    value = spokenInput,
                    onValueChange = { spokenInput = it },
                    placeholder = {
                        Text("Speak or type: 'Should we migrate to Go from Kotlin; budget is $20k...'", fontSize = 12.sp)
                    },
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = cc.accent,
                        unfocusedBorderColor = cc.border
                    )
                )

                if (spokenInput.isNotBlank()) {
                    // Auto-structured preview
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(cc.panelAlt)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Auto-Structured Council Preview:",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = cc.accent
                        )
                        Text(
                            text = "Topic: ${draft.topic}",
                            fontSize = 11.sp,
                            color = cc.textPrimary
                        )
                        if (draft.constraints.isNotBlank()) {
                            Text(
                                text = "Constraints: ${draft.constraints}",
                                fontSize = 11.sp,
                                color = cc.textMuted
                            )
                        }
                        Text(
                            text = "Recommended: ${matchedPreset.title}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = cc.accent
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = cc.textMuted)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (spokenInput.isNotBlank()) {
                                onLaunchCouncil(draft, matchedPreset)
                                onDismiss()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = cc.accent, contentColor = Color.Black),
                        enabled = spokenInput.isNotBlank()
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Convene Council", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
