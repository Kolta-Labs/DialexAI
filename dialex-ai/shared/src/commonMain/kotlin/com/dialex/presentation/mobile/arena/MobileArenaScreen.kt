package com.dialex.presentation.mobile.arena

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.model.Discussion
import com.dialex.model.DiscussionStatus
import com.dialex.theme.LocalCcColors
import kotlinx.collections.immutable.toImmutableList

@Composable
fun MobileArenaScreen(
    state: ArenaContract.State,
    onIntent: (ArenaContract.Intent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cc = LocalCcColors.current
    val discussion = state.discussion
    val isRunning = discussion?.status == DiscussionStatus.RUNNING
    val isPaused = discussion?.status == DiscussionStatus.PAUSED
    val isDone = discussion?.status == DiscussionStatus.DONE

    val agents = discussion?.config?.agents ?: emptyList()
    val currentTurns = state.activeRoundTurns

    Box(modifier = modifier.fillMaxSize().background(cc.bg)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(cc.panel)
                    .border(1.dp, cc.border)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = cc.textPrimary
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text(
                            text = discussion?.name ?: "Deliberation Arena",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = cc.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (isRunning) "Active Deliberation" else if (isDone) "Consensus Concluded" else "Paused",
                            fontSize = 11.sp,
                            color = if (isRunning) Color(0xFF10B981) else cc.textMuted
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (isRunning) {
                        IconButton(onClick = { onIntent(ArenaContract.Intent.PauseDebate) }) {
                            Icon(Icons.Default.Pause, contentDescription = "Pause", tint = cc.accent)
                        }
                    } else if (isPaused) {
                        IconButton(onClick = { onIntent(ArenaContract.Intent.ResumeDebate) }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Resume", tint = Color(0xFF10B981))
                        }
                    }
                }
            }

            // Body: Arena Stage & Carousel
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Circular Arena Stage
                RoundtableArenaStage(
                    agents = agents,
                    activeSpeakerIndex = state.activeSpeakerIndex,
                    isSpeaking = state.isSpeaking,
                    consensusScore = state.consensusScore,
                    currentRound = state.currentRound,
                    maxRounds = state.maxRounds,
                    modifier = Modifier.fillMaxWidth()
                )

                // Perspective Carousel
                PerspectiveCarousel(
                    turns = currentTurns,
                    selectedIndex = state.selectedTurnIndex,
                    onSelectTurn = { onIntent(ArenaContract.Intent.SelectTurn(it)) },
                    modifier = Modifier.weight(1f)
                )
            }

            // Bottom Action Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(cc.panel)
                    .border(1.dp, cc.border)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Interject Button ("Speak at the Table")
                Button(
                    onClick = { onIntent(ArenaContract.Intent.OpenInterjectionDialog) },
                    colors = ButtonDefaults.buttonColors(containerColor = cc.panelAlt, contentColor = cc.textPrimary),
                    modifier = Modifier.weight(1f).height(42.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, cc.accent)
                ) {
                    Icon(Icons.Default.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(16.dp), tint = cc.accent)
                    Spacer(Modifier.width(6.dp))
                    Text("Interject", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }

                // Executive Memo / Deliverables Button
                Button(
                    onClick = { onIntent(ArenaContract.Intent.OpenExecutiveMemo) },
                    colors = ButtonDefaults.buttonColors(containerColor = cc.accent, contentColor = Color.Black),
                    modifier = Modifier.weight(1f).height(42.dp)
                ) {
                    Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Executive Memo", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Interjection Dialog Modal
        if (state.isInterjectionDialogOpen) {
            HumanInterjectionDialog(
                onDismiss = { onIntent(ArenaContract.Intent.CloseInterjectionDialog) },
                onSubmitInterjection = { onIntent(ArenaContract.Intent.SubmitInterjection(it)) }
            )
        }
    }
}
