package com.dialex.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.dialex.model.ApiKeys
import com.dialex.model.CliCommands
import com.dialex.model.Provider
import com.dialex.model.brandName
import com.dialex.service.CliToolDescriptor
import com.dialex.service.SupportedCliTools
import com.dialex.service.launchCliLoginTerminal
import com.dialex.theme.LocalAppColors
import com.dialex.theme.ThemeMode
import com.dialex.theme.accentColor
import com.dialex.ui.cliauth.CliAuthDialog

/**
 * Modern, beautifully styled Settings & Configuration Dialog adhering to the Dialex Design System.
 */
@Composable
fun SettingsDialog(
    currentKeys: ApiKeys,
    currentCommands: CliCommands,
    currentCompactionModel: String,
    currentTokenBudget: Int,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    connectionLabel: String,
    onSwitchConnection: (() -> Unit)? = null,
    extraTabLabel: String? = null,
    extraTabContent: (@Composable () -> Unit)? = null,
    onDismiss: () -> Unit,
    onSave: (ApiKeys, CliCommands, String, Int) -> Unit,
) {
    val cc = LocalAppColors.current

    var anthropicKey by remember { mutableStateOf(currentKeys.anthropic) }
    var openaiKey by remember { mutableStateOf(currentKeys.openai) }
    var geminiKey by remember { mutableStateOf(currentKeys.gemini) }
    var grokKey by remember { mutableStateOf(currentKeys.grok) }
    var deepseekKey by remember { mutableStateOf(currentKeys.deepseek) }
    var mistralKey by remember { mutableStateOf(currentKeys.mistral) }
    var ollamaEndpoint by remember { mutableStateOf(currentKeys.ollama) }

    var anthropicCli by remember { mutableStateOf(currentCommands.anthropic) }
    var openaiCli by remember { mutableStateOf(currentCommands.openai) }
    var geminiCli by remember { mutableStateOf(currentCommands.gemini) }
    var grokCli by remember { mutableStateOf(currentCommands.grok) }
    var deepseekCli by remember { mutableStateOf(currentCommands.deepseek) }
    var mistralCli by remember { mutableStateOf(currentCommands.mistral) }
    var ollamaCli by remember { mutableStateOf(currentCommands.ollama) }
    var customCli by remember { mutableStateOf(currentCommands.custom) }

    var compactionModel by remember { mutableStateOf(currentCompactionModel) }
    var tokenBudgetText by remember { mutableStateOf(currentTokenBudget.toString()) }
    var selectedTabIndex by remember { mutableStateOf(1) } // Default to Providers
    var activeAuthTool by remember { mutableStateOf<CliToolDescriptor?>(null) }
    var confirmingSwitch by remember { mutableStateOf(false) }

    val tabLabels = buildList {
        add("Providers & Auth")
        add("Appearance")
        add("Compaction")
        add("Limits")
        add("Connection")
        if (extraTabLabel != null && extraTabContent != null) add(extraTabLabel)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = cc.panel,
            border = BorderStroke(1.dp, cc.border.copy(alpha = 0.6f)),
            modifier = Modifier
                .width(580.dp)
                .heightIn(max = 660.dp)
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // ── 1. Dialog Header ──────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(cc.accent.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.Settings, contentDescription = null, tint = cc.accent, modifier = Modifier.size(18.dp))
                        }
                        Column {
                            Text(
                                "Settings & Configuration",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
                                color = cc.textPrimary
                            )
                            Text(
                                "Manage AI model providers, authentication, and system policies",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                color = cc.textMuted
                            )
                        }
                    }

                    ThemedTooltipBox("Close") {
                        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Outlined.Close, contentDescription = "Close", tint = cc.textMuted, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ── 2. Segmented Pill Tab Bar ─────────────────────────────────
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = cc.panelAlt,
                    border = BorderStroke(0.5.dp, cc.border.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        tabLabels.forEachIndexed { index, label ->
                            val isSelected = selectedTabIndex == index
                            Surface(
                                shape = RoundedCornerShape(7.dp),
                                color = if (isSelected) cc.panel else Color.Transparent,
                                border = if (isSelected) BorderStroke(0.5.dp, cc.border.copy(alpha = 0.5f)) else null,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(7.dp))
                                    .clickable { selectedTabIndex = index }
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 11.sp,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                        ),
                                        color = if (isSelected) cc.textPrimary else cc.textMuted,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ── 3. Scrollable Tab Content ─────────────────────────────────
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (selectedTabIndex) {
                        // ── Tab 0: Providers & Login ──────────────────────────
                        0 -> {
                            val providersList = listOf(
                                Provider.ANTHROPIC to anthropicKey,
                                Provider.OPENAI to openaiKey,
                                Provider.GEMINI to geminiKey,
                                Provider.GROK to grokKey,
                                Provider.DEEPSEEK to deepseekKey,
                                Provider.MISTRAL to mistralKey,
                                Provider.CUSTOM to ""
                            )

                            providersList.forEach { (prov, _) ->
                                val cliTool = SupportedCliTools.firstOrNull { it.provider == prov }
                                val provAccent = prov.accentColor(cc)
                                val currentKey = when (prov) {
                                    Provider.ANTHROPIC -> anthropicKey
                                    Provider.OPENAI -> openaiKey
                                    Provider.GEMINI -> geminiKey
                                    Provider.GROK -> grokKey
                                    Provider.DEEPSEEK -> deepseekKey
                                    Provider.MISTRAL -> mistralKey
                                    Provider.OLLAMA -> ollamaEndpoint
                                    Provider.CUSTOM -> ""
                                }
                                val currentCmd = when (prov) {
                                    Provider.ANTHROPIC -> anthropicCli
                                    Provider.OPENAI -> openaiCli
                                    Provider.GEMINI -> geminiCli
                                    Provider.GROK -> grokCli
                                    Provider.DEEPSEEK -> deepseekCli
                                    Provider.MISTRAL -> mistralCli
                                    Provider.OLLAMA -> ollamaCli
                                    Provider.CUSTOM -> customCli
                                }

                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = cc.panelAlt,
                                    border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.35f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        // Row 1: Header (Brand Name, Badge, Login/Logout Button)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .clip(CircleShape)
                                                        .background(provAccent)
                                                )
                                                Text(
                                                    prov.brandName(),
                                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp),
                                                    color = cc.textPrimary
                                                )
                                                if (cliTool != null) {
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = if (cc.isDark) Color(0xFF14151A) else Color(0xFFE5E7EB)
                                                    ) {
                                                        Text(
                                                            cliTool.binaryName,
                                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 9.5.sp),
                                                            color = cc.textMuted,
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.5.dp)
                                                        )
                                                    }
                                                }
                                            }

                                            // Action Buttons (In-App Login / Clear Key)
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                if (cliTool != null) {
                                                    GradientButton(
                                                        text = "Log In",
                                                        onClick = { launchCliLoginTerminal(cliTool.loginCommand) },
                                                        icon = Icons.AutoMirrored.Outlined.Login,
                                                        height = 26.dp,
                                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                                                    )
                                                }

                                                if (currentKey.isNotBlank()) {
                                                    OutlinedButton(
                                                        onClick = {
                                                            when (prov) {
                                                                Provider.ANTHROPIC -> anthropicKey = ""
                                                                Provider.OPENAI -> openaiKey = ""
                                                                Provider.GEMINI -> geminiKey = ""
                                                                Provider.GROK -> grokKey = ""
                                                                Provider.DEEPSEEK -> deepseekKey = ""
                                                                Provider.MISTRAL -> mistralKey = ""
                                                                else -> {}
                                                            }
                                                        },
                                                        shape = RoundedCornerShape(6.dp),
                                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                        modifier = Modifier.height(26.dp)
                                                    ) {
                                                        Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = "Clear Key", modifier = Modifier.size(11.dp), tint = cc.textMuted)
                                                        Spacer(Modifier.width(3.dp))
                                                        Text("Clear Key", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = cc.textMuted)
                                                    }
                                                }
                                            }
                                        }

                                        // Row 2: API Key Input (if not Custom)
                                        if (prov != Provider.CUSTOM) {
                                            AestheticSettingsField(
                                                value = currentKey,
                                                onValueChange = { newVal ->
                                                    when (prov) {
                                                        Provider.ANTHROPIC -> anthropicKey = newVal
                                                        Provider.OPENAI -> openaiKey = newVal
                                                        Provider.GEMINI -> geminiKey = newVal
                                                        Provider.GROK -> grokKey = newVal
                                                        Provider.DEEPSEEK -> deepseekKey = newVal
                                                        Provider.MISTRAL -> mistralKey = newVal
                                                        Provider.OLLAMA -> ollamaEndpoint = newVal
                                                        else -> {}
                                                    }
                                                },
                                                label = if (prov == Provider.OLLAMA) "Ollama Endpoint" else "${prov.brandName()} API Key",
                                                isSecret = prov != Provider.OLLAMA,
                                                cc = cc
                                            )
                                        }

                                        // Row 3: CLI Command Input
                                        AestheticSettingsField(
                                            value = currentCmd,
                                            onValueChange = { newVal ->
                                                when (prov) {
                                                    Provider.ANTHROPIC -> anthropicCli = newVal
                                                    Provider.OPENAI -> openaiCli = newVal
                                                    Provider.GEMINI -> geminiCli = newVal
                                                    Provider.GROK -> grokCli = newVal
                                                    Provider.DEEPSEEK -> deepseekCli = newVal
                                                    Provider.MISTRAL -> mistralCli = newVal
                                                    Provider.OLLAMA -> ollamaCli = newVal
                                                    Provider.CUSTOM -> customCli = newVal
                                                }
                                            },
                                            label = if (prov == Provider.CUSTOM) "Custom CLI Command" else "CLI Command (${cliTool?.binaryName ?: "cli"})",
                                            isSecret = false,
                                            cc = cc
                                        )
                                    }
                                }
                            }
                        }

                        // ── Tab 1: Appearance ─────────────────────────────────
                        1 -> {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = cc.panelAlt,
                                border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.35f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("THEME MODE", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = cc.textMuted)
                                    Text(
                                        "Select your preferred visual style. System mode synchronizes with your OS appearance.",
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                        color = cc.textMuted
                                    )
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (cc.isDark) Color(0xFF14151A) else Color(0xFFE5E7EB))
                                            .padding(3.dp),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        ThemeMode.entries.forEach { mode ->
                                            val isSel = mode == themeMode
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = if (isSel) cc.panel else Color.Transparent,
                                                border = if (isSel) BorderStroke(0.5.dp, cc.border.copy(alpha = 0.5f)) else null,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .clickable { onThemeModeChange(mode) }
                                            ) {
                                                Box(modifier = Modifier.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                                                    Text(
                                                        mode.name.lowercase().replaceFirstChar { it.uppercase() },
                                                        style = MaterialTheme.typography.labelMedium.copy(
                                                            fontSize = 12.sp,
                                                            fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal
                                                        ),
                                                        color = if (isSel) cc.textPrimary else cc.textMuted
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // ── Tab 2: Compaction ─────────────────────────────────
                        2 -> {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = cc.panelAlt,
                                border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.35f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("COMPACTION MODEL", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = cc.textMuted)
                                    Text(
                                        "Long debates get older turns summarized to save token costs and prevent context exhaustion. Compaction uses this model.",
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                        color = cc.textMuted
                                    )
                                    AestheticSettingsField(
                                        value = compactionModel,
                                        onValueChange = { compactionModel = it },
                                        label = "Model Name",
                                        isSecret = false,
                                        cc = cc
                                    )
                                }
                            }
                        }

                        // ── Tab 3: Limits ─────────────────────────────────────
                        3 -> {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = cc.panelAlt,
                                border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.35f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("TOKEN BUDGET", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = cc.textMuted)
                                    Text(
                                        "Maximum tokens per discussion before auto-pausing. Enter 0 to disable.",
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                        color = cc.textMuted
                                    )
                                    AestheticSettingsField(
                                        value = tokenBudgetText,
                                        onValueChange = { if (it.all(Char::isDigit)) tokenBudgetText = it },
                                        label = "Max Tokens",
                                        isSecret = false,
                                        cc = cc
                                    )
                                }
                            }
                        }

                        // ── Tab 4: Connection ─────────────────────────────────
                        4 -> {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = cc.panelAlt,
                                border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.35f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("ENGINE CONNECTION", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = cc.textMuted)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (cc.isDark) Color(0xFF14151A) else Color(0xFFE5E7EB))
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF4CAF50)))
                                        Text(connectionLabel, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp), color = cc.textPrimary)
                                    }

                                    if (onSwitchConnection != null) {
                                        OutlinedButton(
                                            onClick = { confirmingSwitch = true },
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                            modifier = Modifier.height(30.dp)
                                        ) {
                                            Text("Switch Engine Connection", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = cc.textPrimary)
                                        }
                                    }
                                }
                            }
                        }

                        else -> extraTabContent?.invoke()
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ── 4. Footer ─────────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Settings apply across all active debate sessions.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = cc.textMuted
                    )

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss, modifier = Modifier.height(32.dp)) {
                            Text("Cancel", style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp), color = cc.textMuted)
                        }

                        GradientButton(
                            text = "Save Changes",
                            onClick = {
                                onSave(
                                    ApiKeys(
                                        anthropic = anthropicKey, openai = openaiKey, gemini = geminiKey,
                                        grok = grokKey, deepseek = deepseekKey, mistral = mistralKey,
                                        ollama = ollamaEndpoint,
                                    ),
                                    CliCommands(
                                        anthropic = anthropicCli, openai = openaiCli, gemini = geminiCli,
                                        grok = grokCli, deepseek = deepseekCli, mistral = mistralCli,
                                        ollama = ollamaCli, custom = customCli,
                                    ),
                                    compactionModel,
                                    tokenBudgetText.toIntOrNull() ?: currentTokenBudget,
                                )
                            },
                            height = 32.dp,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }

    if (activeAuthTool != null) {
        CliAuthDialog(
            tool = activeAuthTool!!,
            onDismiss = { activeAuthTool = null },
            onSuccess = { activeAuthTool = null }
        )
    }

    if (confirmingSwitch && onSwitchConnection != null) {
        ConfirmDialog(
            title = "Switch Connection",
            message = "Disconnect from the current engine? You will be asked to reconnect.",
            confirmLabel = "Switch",
            onDismiss = { confirmingSwitch = false },
            onConfirm = { onSwitchConnection() },
        )
    }
}

@Composable
private fun AestheticSettingsField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isSecret: Boolean = false,
    cc: com.dialex.theme.CcPalette,
) {
    var passwordVisible by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(label, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp), color = cc.textMuted.copy(alpha = 0.6f)) },
        singleLine = true,
        visualTransformation = if (isSecret && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = MaterialTheme.typography.bodySmall.copy(
            fontFamily = if (isSecret) FontFamily.Default else FontFamily.Monospace,
            fontSize = 11.5.sp,
            color = cc.textPrimary
        ),
        trailingIcon = if (isSecret && value.isNotEmpty()) {
            {
                IconButton(onClick = { passwordVisible = !passwordVisible }, modifier = Modifier.size(24.dp)) {
                    Icon(
                        if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = "Toggle password visibility",
                        tint = cc.textMuted,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        } else null,
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = cc.accent,
            unfocusedBorderColor = cc.border.copy(alpha = 0.4f),
            focusedContainerColor = if (cc.isDark) Color(0xFF14151A) else Color(0xFFFFFFFF),
            unfocusedContainerColor = if (cc.isDark) Color(0xFF14151A) else Color(0xFFFFFFFF),
        ),
        modifier = Modifier.fillMaxWidth().height(42.dp)
    )
}
