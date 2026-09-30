package com.dialex.presentation.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.model.Provider
import com.dialex.presentation.chat.ChatTypographySettings
import com.dialex.theme.CcPalette
import com.dialex.ui.GradientButton
import kotlinx.coroutines.delay

@Composable
fun CheckpointDivider(
    checkpointNumber: Int,
    cc: CcPalette,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(modifier = Modifier.weight(1f).height(2.dp)) {
            drawLine(
                color = cc.accent.copy(alpha = 0.55f),
                start = Offset(0f, size.height / 2),
                end = Offset(size.width, size.height / 2),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f),
                strokeWidth = 1.5f
            )
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = cc.panelAlt,
            border = BorderStroke(0.75.dp, cc.accent.copy(alpha = 0.45f)),
            modifier = Modifier.padding(horizontal = 10.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(cc.accent)
                )
                Text(
                    text = "Checkpoint $checkpointNumber",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.5.sp
                    ),
                    color = cc.accent
                )
            }
        }

        Canvas(modifier = Modifier.weight(1f).height(2.dp)) {
            drawLine(
                color = cc.accent.copy(alpha = 0.55f),
                start = Offset(0f, size.height / 2),
                end = Offset(size.width, size.height / 2),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f),
                strokeWidth = 1.5f
            )
        }
    }
}

@Composable
fun RoundDivider(
    round: Int,
    totalRounds: Int,
    cc: CcPalette,
    modifier: Modifier = Modifier
) {
    val phaseLabel = when (round) {
        1 -> "Opening Statements"
        totalRounds -> "Closing Arguments & Synthesis"
        else -> "Cross-Examination & Rebuttals"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = cc.border.copy(alpha = 0.45f),
            thickness = 0.75.dp
        )

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = cc.panelAlt,
            border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.5f)),
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(cc.accent)
                )
                Text(
                    text = "Round $round" + if (totalRounds > 0) " of $totalRounds" else "",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.5.sp
                    ),
                    color = cc.textPrimary
                )
                Text(
                    text = "· $phaseLabel",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = cc.textMuted
                )
            }
        }

        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = cc.border.copy(alpha = 0.45f),
            thickness = 0.75.dp
        )
    }
}

enum class TurnErrorCategory {
    SESSION_OR_RATE_LIMIT,
    AUTH_REQUIRED,
    CLI_PROCESS_FAILURE,
    NETWORK_OR_SERVER,
    GENERIC
}

data class ParsedTurnError(
    val category: TurnErrorCategory,
    val badgeLabel: String,
    val badgeIcon: String,
    val badgeColor: Color,
    val title: String,
    val summary: String,
    val resetSchedule: String? = null,
    val rawError: String,
    val cliBinary: String = ""
)

fun parseTurnError(content: String, provider: Provider): ParsedTurnError {
    val cliBinary = extractCliBinary(content, provider)
    val lower = content.lowercase()

    // 1. Reset schedule extraction if present
    val resetRegex = Regex("""(?:resets?|resets at|reset in|retry in|retry after|try again in)\s+([^\n\r·•\.]+)""", RegexOption.IGNORE_CASE)
    val resetMatch = resetRegex.find(content)?.groupValues?.get(1)?.trim()

    // 2. Token / Session / Quota / Rate limit detection
    val isSessionLimit = lower.contains("session limit") || lower.contains("session_limit") || lower.contains("sessionlimit")
    val isRateLimit = lower.contains("rate limit") || lower.contains("rate_limit") || lower.contains("429") || lower.contains("too many requests")
    val isQuotaExhausted = lower.contains("insufficient_quota") || lower.contains("exceeded your current quota") ||
            lower.contains("credit balance is too low") || lower.contains("insufficient credits") || lower.contains("resource_exhausted") ||
            lower.contains("quota exceeded")
    val isTokenBudgetExhausted = lower.contains("token budget") || lower.contains("token limit") || lower.contains("tokens exhausted")
    val isContextLengthExceeded = lower.contains("context length") || lower.contains("maximum context length") || lower.contains("prompt is too long")

    if (isSessionLimit || isRateLimit || isQuotaExhausted || isTokenBudgetExhausted || isContextLengthExceeded) {
        val badgeLabel = when {
            isSessionLimit -> "Session Limit Hit"
            isQuotaExhausted -> "Quota Exhausted"
            isTokenBudgetExhausted -> "Token Budget Reached"
            isContextLengthExceeded -> "Context Window Exceeded"
            else -> "Rate Limited (429)"
        }
        val badgeIcon = when {
            isQuotaExhausted -> "💳"
            isContextLengthExceeded -> "📏"
            else -> "⏳"
        }
        val title = when {
            isSessionLimit -> "Session Limit Reached"
            isQuotaExhausted -> "Account Quota Exhausted"
            isTokenBudgetExhausted -> "Token Budget Limit Reached"
            isContextLengthExceeded -> "Context Window Exceeded"
            else -> "API Rate Limit Hit"
        }
        val friendlyMsg = when {
            isSessionLimit -> "The provider CLI session ($cliBinary) reached its active quota limit.${if (resetMatch != null) " Access will reset automatically at the scheduled time below." else " Please wait until reset or switch provider/model in Settings."}"
            isQuotaExhausted -> "The provider account has exhausted its available credits or billing quota. Please check your provider billing dashboard or configure an alternate API key."
            isTokenBudgetExhausted -> "This debate reached its configured safety Token Budget. You can raise or disable the limit in Settings > Limits."
            isContextLengthExceeded -> "The conversation history exceeds the maximum context length supported by this model. Consider compacting the history or reducing file attachments."
            else -> "Too many requests sent to the provider. The request was temporarily throttled."
        }
        return ParsedTurnError(
            category = TurnErrorCategory.SESSION_OR_RATE_LIMIT,
            badgeLabel = badgeLabel,
            badgeIcon = badgeIcon,
            badgeColor = if (isQuotaExhausted) Color(0xFFEF4444) else Color(0xFFF59E0B),
            title = title,
            summary = friendlyMsg,
            resetSchedule = resetMatch,
            rawError = content,
            cliBinary = cliBinary
        )
    }

    // 3. Auth / Login required
    val isAuth = lower.contains("auth") || lower.contains("log in") || lower.contains("login") ||
            lower.contains("unauthorized") || lower.contains("401") || lower.contains("re-authentication") ||
            lower.contains("api key missing") || lower.contains("invalid api key") ||
            lower.contains("input must be provided") || lower.contains("stdin")

    if (isAuth) {
        return ParsedTurnError(
            category = TurnErrorCategory.AUTH_REQUIRED,
            badgeLabel = "Login Required",
            badgeIcon = "🔑",
            badgeColor = Color(0xFFE11D48),
            title = "Authentication Required",
            summary = "The local CLI tool ($cliBinary) or API provider requires authentication. Click below to log in or configure your API credentials in Settings.",
            resetSchedule = null,
            rawError = content,
            cliBinary = cliBinary
        )
    }

    // 4. CLI / Process failure
    val isProcessError = lower.contains("command not found") || lower.contains("executable file not found") ||
            lower.contains("exit status") || lower.contains("exit code") || lower.contains("exited with an error")

    if (isProcessError) {
        return ParsedTurnError(
            category = TurnErrorCategory.CLI_PROCESS_FAILURE,
            badgeLabel = "CLI Error",
            badgeIcon = "⚙️",
            badgeColor = Color(0xFFEF4444),
            title = "CLI Process Error",
            summary = "The local execution tool encountered an exit error or failed to launch. Verify '$cliBinary' is installed in your PATH and functioning correctly.",
            resetSchedule = null,
            rawError = content,
            cliBinary = cliBinary
        )
    }

    // 5. Network / Server error
    val isNetwork = lower.contains("timeout") || lower.contains("connection refused") ||
            lower.contains("network") || lower.contains("502") || lower.contains("503") || lower.contains("504")

    if (isNetwork) {
        return ParsedTurnError(
            category = TurnErrorCategory.NETWORK_OR_SERVER,
            badgeLabel = "Network Error",
            badgeIcon = "🌐",
            badgeColor = Color(0xFFEF4444),
            title = "Network Connection Error",
            summary = "Failed to establish a connection to the provider or backend engine. Check your network connection or click Retry to rerun the turn.",
            resetSchedule = null,
            rawError = content,
            cliBinary = cliBinary
        )
    }

    // Default generic
    return ParsedTurnError(
        category = TurnErrorCategory.GENERIC,
        badgeLabel = "Turn Dropped",
        badgeIcon = "⚠️",
        badgeColor = Color(0xFFEF4444),
        title = "Turn Dropped",
        summary = "An unexpected error interrupted this turn's response.",
        resetSchedule = null,
        rawError = content,
        cliBinary = cliBinary
    )
}

fun extractCliBinary(content: String, provider: Provider): String {
    val cliRegex = Regex("""CLI\s+['"]?([^'"\s]+)""", RegexOption.IGNORE_CASE).find(content)?.groupValues?.get(1)
    if (!cliRegex.isNullOrBlank()) return cliRegex
    val runMatch = Regex("""Run\s+['"]?(\w+)['"]?\s+to\s+log\s+in""", RegexOption.IGNORE_CASE).find(content)?.groupValues?.get(1)
    if (!runMatch.isNullOrBlank()) return runMatch
    val quoteMatch = Regex("""'(.*?)'""").find(content)?.groupValues?.get(1)
    if (!quoteMatch.isNullOrBlank()) {
        val firstWord = quoteMatch.trim().split(Regex("""\s+""")).firstOrNull().orEmpty()
        if (firstWord.isNotBlank() && firstWord.length < 20) return firstWord
    }
    return when (provider) {
        Provider.ANTHROPIC -> "claude"
        Provider.GEMINI -> "agy"
        Provider.OPENAI -> "codex"
        else -> "agy"
    }
}

@Composable
fun TurnErrorBlock(
    errorInfo: ParsedTurnError,
    cc: CcPalette,
    typographySettings: ChatTypographySettings,
    isCliLoggedIn: Boolean,
    onLoginCli: ((String) -> Unit)?,
    onRetry: (() -> Unit)?
) {
    var isExpandedDetails by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1800)
            copied = false
        }
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = errorInfo.badgeColor.copy(alpha = if (cc.isDark) 0.08f else 0.05f),
        border = BorderStroke(1.dp, errorInfo.badgeColor.copy(alpha = if (cc.isDark) 0.35f else 0.25f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Title Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(errorInfo.badgeColor.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(errorInfo.badgeIcon, fontSize = 14.sp)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = errorInfo.title,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp
                        ),
                        color = cc.textPrimary
                    )
                }
            }

            // Friendly Message
            Text(
                text = errorInfo.summary,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = typographySettings.fontSizeSp.sp,
                    lineHeight = (typographySettings.fontSizeSp * 1.35f).sp
                ),
                color = cc.textPrimary.copy(alpha = 0.88f)
            )

            // Reset Schedule Banner
            if (!errorInfo.resetSchedule.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = errorInfo.badgeColor.copy(alpha = if (cc.isDark) 0.15f else 0.10f),
                    border = BorderStroke(0.75.dp, errorInfo.badgeColor.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Outlined.AccessTime,
                            contentDescription = null,
                            tint = errorInfo.badgeColor,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "Resets: ",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Normal,
                                fontSize = 11.5.sp
                            ),
                            color = cc.textMuted
                        )
                        Text(
                            text = errorInfo.resetSchedule,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            ),
                            color = errorInfo.badgeColor
                        )
                    }
                }
            }

            // Collapsible Technical Log / Raw Error
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { isExpandedDetails = !isExpandedDetails }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        if (isExpandedDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = cc.textMuted,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = if (isExpandedDetails) "Hide technical error details" else "View technical error details",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                        color = cc.textMuted
                    )
                }

                if (isExpandedDetails) {
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = cc.panel.copy(alpha = 0.8f),
                        border = BorderStroke(0.5.dp, cc.border.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SelectionContainer {
                                Text(
                                    text = errorInfo.rawError,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp,
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    color = cc.textMuted
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable {
                                            clipboard.setText(AnnotatedString(errorInfo.rawError))
                                            copied = true
                                        }
                                        .padding(horizontal = 6.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                                        contentDescription = "Copy",
                                        tint = if (copied) cc.accent else cc.textMuted,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = if (copied) "Copied" else "Copy error",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                        color = if (copied) cc.accent else cc.textMuted
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Action Buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                if (isCliLoggedIn) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = cc.accent.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, cc.accent.copy(alpha = 0.5f)),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                tint = cc.accent,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                "${errorInfo.cliBinary} Ready",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp
                                ),
                                color = cc.textPrimary
                            )
                        }
                    }

                    if (onRetry != null) {
                        GradientButton(
                            text = "Resume Discussion",
                            icon = Icons.Outlined.PlayArrow,
                            onClick = onRetry,
                            height = 32.dp,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                } else if (errorInfo.category == TurnErrorCategory.AUTH_REQUIRED && onLoginCli != null) {
                    GradientButton(
                        text = "Log in with ${errorInfo.cliBinary}",
                        icon = Icons.AutoMirrored.Outlined.Login,
                        onClick = { onLoginCli.invoke(errorInfo.cliBinary) },
                        height = 32.dp,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    )

                    if (onRetry != null) {
                        OutlinedButton(
                            onClick = onRetry,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, cc.border.copy(alpha = 0.7f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = Color.Transparent,
                                contentColor = cc.textPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Retry", tint = cc.textPrimary, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Retry", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp), color = cc.textPrimary)
                        }
                    }
                } else if (onRetry != null) {
                    GradientButton(
                        text = "Retry Turn",
                        icon = Icons.Outlined.Refresh,
                        onClick = onRetry,
                        height = 32.dp,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}
