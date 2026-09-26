package com.dialex.presentation.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.outlined.Psychology
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
import com.dialex.domain.model.ConnectionProfile
import com.dialex.domain.model.ProfileType
import com.dialex.domain.repository.ApiKeyRepository
import com.dialex.domain.repository.PersonaRepository
import com.dialex.domain.repository.ProfileRepository
import com.dialex.model.PredefinedPersona
import com.dialex.theme.LocalCcColors
import com.dialex.theme.ThemeMode
import kotlinx.coroutines.launch

@Composable
fun MobileVaultTab(
    activeProfile: ConnectionProfile?,
    profileRepository: ProfileRepository,
    apiKeyRepository: ApiKeyRepository,
    onSwitchToLocal: () -> Unit,
    onSwitchToRemote: () -> Unit,
    onScanQr: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    personaRepository: PersonaRepository? = null,
    onOpenPersonaBuilder: (String?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val cc = LocalCcColors.current
    val currentType = activeProfile?.type ?: ProfileType.LOCAL
    val coroutineScope = rememberCoroutineScope()
    var personas by remember { mutableStateOf<List<PredefinedPersona>>(emptyList()) }

    LaunchedEffect(personaRepository) {
        if (personaRepository != null) {
            try {
                personas = personaRepository.getPersonas()
            } catch (e: Exception) {
                // Ignore load error
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp)
    ) {
        item {
            Text(
                text = "Vault & Cognitive Studio",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = cc.textPrimary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Manage execution engine, 8-layer persona DNAs, hardware-backed keys, and pairing.",
                style = MaterialTheme.typography.bodySmall,
                color = cc.textMuted
            )
        }

        item {
            // Engine Mode Switcher
            EngineModeSwitcher(
                activeProfileType = currentType,
                remoteUrl = activeProfile?.remoteUrl,
                onSelectLocal = onSwitchToLocal,
                onSelectRemote = onSwitchToRemote
            )
        }

        // 8-Layer Persona Studio & Cognitive DNA Section
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(cc.panel)
                    .border(1.dp, cc.border, RoundedCornerShape(12.dp))
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                imageVector = Icons.Outlined.Psychology,
                                contentDescription = null,
                                tint = cc.accent,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = "Personas & 8-Layer DNA",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = cc.textPrimary
                                )
                                Text(
                                    text = "Epistemic mental models & archetypes",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = cc.textMuted
                                )
                            }
                        }

                        Button(
                            onClick = { onOpenPersonaBuilder(null) },
                            colors = ButtonDefaults.buttonColors(containerColor = cc.accent, contentColor = Color.Black),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("New", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    HorizontalDivider(color = cc.border.copy(alpha = 0.4f), thickness = 0.5.dp)

                    if (personas.isEmpty()) {
                        Text(
                            text = "No custom personas created yet. Tap '+ New' to calibrate an 8-layer cognitive DNA profile.",
                            style = MaterialTheme.typography.bodySmall,
                            color = cc.textMuted
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            personas.take(6).forEach { persona ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(cc.bg)
                                        .border(0.75.dp, cc.border.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                        .clickable { onOpenPersonaBuilder(persona.id) }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(
                                                text = persona.name,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                color = cc.textPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (persona.isSystem) {
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(cc.panelAlt)
                                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                                ) {
                                                    Text("System", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = cc.textMuted)
                                                }
                                            }
                                        }
                                        if (persona.role.isNotBlank()) {
                                            Text(
                                                text = persona.role,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = cc.accent,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    Icon(
                                        imageVector = Icons.Default.ChevronRight,
                                        contentDescription = null,
                                        tint = cc.textMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (currentType == ProfileType.REMOTE) {
            item {
                // Remote Connection Details & QR Scanner Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(cc.panel)
                        .border(1.dp, cc.border, RoundedCornerShape(12.dp))
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Remote Server Connection",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = cc.textPrimary
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF10B981).copy(alpha = 0.15f))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "Connected",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF10B981)
                                )
                            }
                        }

                        Text(
                            text = "Server URL: ${activeProfile?.remoteUrl ?: "http://127.0.0.1:7890"}",
                            fontSize = 12.sp,
                            color = cc.textMuted
                        )

                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = onScanQr,
                            colors = ButtonDefaults.buttonColors(containerColor = cc.accent, contentColor = Color.Black),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Scan Desktop QR Code to Pair", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else {
            item {
                // On-Device API Keys Editor
                MobileApiKeySettingsView(
                    profileId = activeProfile?.id ?: ConnectionProfile.LOCAL_PROFILE_ID,
                    apiKeyRepository = apiKeyRepository
                )
            }
        }

        item {
            // Appearance Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(cc.panel)
                    .border(1.dp, cc.border, RoundedCornerShape(12.dp))
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Appearance Theme",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = cc.textPrimary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ThemeMode.entries.forEach { mode ->
                            val isSelected = themeMode == mode
                            Button(
                                onClick = { onThemeModeChange(mode) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSelected) cc.accent else cc.panelAlt,
                                    contentColor = if (isSelected) Color.Black else cc.textPrimary
                                ),
                                modifier = Modifier.weight(1f).height(36.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
