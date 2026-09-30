package com.dialex.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.theme.LocalCcColors

/**
 * Universally styled, high-contrast Toast / Snackbar host respecting the
 * design system with crisp readable text and subtle micro-borders.
 */
@Composable
fun ThemedSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    val cc = LocalCcColors.current
    val containerBg = if (cc.isDark) Color(0xFF22222C) else Color(0xFF1E293B)
    val containerBorder = if (cc.isDark) Color(0xFF3E3E4E) else Color(0xFF334155)
    val textColor = Color(0xFFF9FAFB) // Crisp luminous white for maximum readability

    SnackbarHost(
        hostState = hostState,
        modifier = modifier
    ) { data ->
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = containerBg,
            border = BorderStroke(1.dp, containerBorder),
            shadowElevation = 6.dp
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .widthIn(min = 280.dp, max = 560.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = data.visuals.message,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    ),
                    color = textColor,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (data.visuals.actionLabel != null) {
                    Spacer(Modifier.width(12.dp))
                    TextButton(
                        onClick = { data.performAction() },
                        colors = ButtonDefaults.textButtonColors(contentColor = cc.accent)
                    ) {
                        Text(
                            text = data.visuals.actionLabel!!,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.5.sp
                            )
                        )
                    }
                }
            }
        }
    }
}
