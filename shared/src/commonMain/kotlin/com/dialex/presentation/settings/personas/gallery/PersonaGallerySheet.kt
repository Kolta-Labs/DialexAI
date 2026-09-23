package com.dialex.presentation.settings.personas.gallery

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.dialex.data.datasource.PersonaGalleryService
import com.dialex.model.PredefinedPersona
import com.dialex.theme.LocalCcColors
import com.dialex.ui.GradientButton
import com.dialex.ui.PersonaIconView

@Composable
fun PersonaGalleryDialog(
    installedPersonaIds: Set<String>,
    onInstallPersona: (PredefinedPersona) -> Unit,
    onDismiss: () -> Unit,
) {
    val cc = LocalCcColors.current
    val uriHandler = LocalUriHandler.current

    var personas by remember { mutableStateOf<List<PredefinedPersona>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedPersona by remember { mutableStateOf<PredefinedPersona?>(null) }

    LaunchedEffect(Unit) {
        isLoading = true
        personas = PersonaGalleryService.fetchGalleryPersonas()
        isLoading = false
    }

    val filtered = remember(personas, searchQuery) {
        if (searchQuery.isBlank()) personas
        else personas.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.category.contains(searchQuery, ignoreCase = true) ||
            it.description.contains(searchQuery, ignoreCase = true) ||
            it.coreExpertise.contains(searchQuery, ignoreCase = true)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            color = cc.panel,
            border = BorderStroke(1.dp, cc.border)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "Persona Community Gallery",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = cc.textPrimary
                        )
                        Text(
                            "Discover and install community debate personas",
                            style = MaterialTheme.typography.bodySmall,
                            color = cc.textMuted
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = {
                                try {
                                    uriHandler.openUri(PersonaGalleryService.DEFAULT_WEB_GALLERY_URL)
                                } catch (_: Exception) {}
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(Icons.Outlined.Language, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Open in Browser", style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = onDismiss, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = cc.textMuted)
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Search Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search gallery personas...", color = cc.textMuted.copy(alpha = 0.7f)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = cc.textMuted) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                )

                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = cc.border.copy(alpha = 0.35f))
                Spacer(Modifier.height(14.dp))

                if (isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = cc.accent)
                    }
                } else if (filtered.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No personas found.", color = cc.textMuted)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filtered, key = { it.id }) { persona ->
                            val isInstalled = installedPersonaIds.contains(persona.id)

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = cc.panelAlt,
                                border = BorderStroke(1.dp, cc.border.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth().clickable {
                                    selectedPersona = persona
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        PersonaIconView(icon = persona.icon, category = persona.category, size = 42.dp)
                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    persona.name,
                                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                                    color = cc.textPrimary
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(12.dp),
                                                    color = cc.accent.copy(alpha = 0.12f),
                                                    contentColor = cc.accent
                                                ) {
                                                    Text(
                                                        persona.category,
                                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                persona.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = cc.textMuted,
                                                maxLines = 2
                                            )
                                        }
                                    }

                                    Spacer(Modifier.width(16.dp))

                                    if (isInstalled) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = cc.accent.copy(alpha = 0.15f),
                                            contentColor = cc.accent
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("Installed", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    } else {
                                        GradientButton(
                                            text = "Install",
                                            icon = Icons.Outlined.Download,
                                            onClick = { onInstallPersona(persona) },
                                            height = 32.dp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
