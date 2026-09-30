package com.dialex.presentation.mobile.arena

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.dialex.theme.LocalCcColors

/**
 * Human Interjection Dialog: "Speak at the Table".
 * Allows users to inject mid-flight constraints or pivots into the deliberation.
 */
@Composable
fun HumanInterjectionDialog(
    onDismiss: () -> Unit,
    onSubmitInterjection: (String) -> Unit
) {
    val cc = LocalCcColors.current
    var instruction by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(cc.panel)
                .border(1.5.dp, cc.accent, RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(cc.accent.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.RecordVoiceOver,
                            contentDescription = null,
                            tint = cc.accent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Speak at the Table",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = cc.textPrimary
                        )
                        Text(
                            text = "Inject a new constraint or direction mid-debate",
                            fontSize = 11.sp,
                            color = cc.textMuted
                        )
                    }
                }

                Text(
                    text = "Agents will pause their current thread and address your directive in their next turn.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cc.textMuted,
                    lineHeight = 16.sp
                )

                OutlinedTextField(
                    value = instruction,
                    onValueChange = { instruction = it },
                    placeholder = {
                        Text("e.g. Assume a $50k budget ceiling and require Postgres...", fontSize = 13.sp)
                    },
                    trailingIcon = {
                        com.dialex.ui.VoiceInputButton(
                            currentText = instruction,
                            onTextChange = { instruction = it },
                            size = 32.dp,
                            iconSize = 18.dp
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = cc.accent,
                        unfocusedBorderColor = cc.border
                    )
                )

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
                            if (instruction.isNotBlank()) {
                                onSubmitInterjection(instruction.trim())
                                onDismiss()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = cc.accent,
                            contentColor = Color.Black
                        ),
                        enabled = instruction.isNotBlank()
                    ) {
                        Text("Inject Directive", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
