package com.dialex.presentation.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.domain.model.ApiKeyStatus
import com.dialex.domain.model.ProfileApiKeysStatus
import com.dialex.domain.repository.ApiKeyRepository
import com.dialex.model.Provider
import com.dialex.theme.LocalCcColors
import kotlinx.coroutines.launch

@Composable
fun MobileApiKeySettingsView(
    profileId: String,
    apiKeyRepository: ApiKeyRepository,
    modifier: Modifier = Modifier
) {
    val cc = LocalCcColors.current
    val coroutineScope = rememberCoroutineScope()
    var keyStatuses by remember { mutableStateOf<ProfileApiKeysStatus?>(null) }
    var editingProvider by remember { mutableStateOf<Provider?>(null) }
    var inputKey by remember { mutableStateOf("") }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }

    fun refreshKeys() {
        coroutineScope.launch {
            keyStatuses = apiKeyRepository.getApiKeyStatuses(profileId)
        }
    }

    LaunchedEffect(profileId) {
        refreshKeys()
    }

    val providers = listOf(
        Provider.ANTHROPIC,
        Provider.OPENAI,
        Provider.GEMINI,
        Provider.GROK,
        Provider.DEEPSEEK,
        Provider.MISTRAL
    )

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "On-Device Direct API Keys",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = cc.textPrimary
        )
        Text(
            text = "Keys are encrypted using device hardware-backed storage. Required when running in Standalone Local Engine mode.",
            style = MaterialTheme.typography.bodySmall,
            color = cc.textMuted
        )

        if (feedbackMessage != null) {
            Text(
                text = feedbackMessage!!,
                style = MaterialTheme.typography.bodySmall,
                color = cc.accent,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        providers.forEach { provider ->
            val isConfigured = keyStatuses?.isConfigured(provider) == true
            val isEditing = editingProvider == provider
            val providerName = provider.name.lowercase().replaceFirstChar { it.uppercase() }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(cc.panel)
                    .border(1.dp, cc.border, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            tint = if (isConfigured) cc.accent else cc.textMuted,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = providerName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = cc.textPrimary
                            )
                            Text(
                                text = if (isConfigured) "Configured (Encrypted)" else "Not Configured",
                                fontSize = 11.sp,
                                color = if (isConfigured) Color(0xFF10B981) else cc.textMuted
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (isConfigured && !isEditing) {
                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        apiKeyRepository.removeApiKey(profileId, provider)
                                        feedbackMessage = "$providerName key removed"
                                        refreshKeys()
                                    }
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Remove Key",
                                    tint = cc.textMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Button(
                            onClick = {
                                if (isEditing) {
                                    editingProvider = null
                                    inputKey = ""
                                } else {
                                    editingProvider = provider
                                    inputKey = ""
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isEditing) cc.panelAlt else cc.accent,
                                contentColor = if (isEditing) cc.textPrimary else Color.Black
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(if (isEditing) "Cancel" else if (isConfigured) "Change" else "Add", fontSize = 12.sp)
                        }
                    }
                }

                if (isEditing) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = inputKey,
                        onValueChange = { inputKey = it },
                        placeholder = { Text("Paste secret key...", fontSize = 13.sp) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = cc.accent,
                            unfocusedBorderColor = cc.border
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            if (inputKey.isNotBlank()) {
                                coroutineScope.launch {
                                    apiKeyRepository.setApiKey(profileId, provider, inputKey.trim())
                                    feedbackMessage = "$providerName key saved securely"
                                    editingProvider = null
                                    inputKey = ""
                                    refreshKeys()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = cc.accent, contentColor = Color.Black),
                        modifier = Modifier.align(Alignment.End).height(32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Save Key", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
