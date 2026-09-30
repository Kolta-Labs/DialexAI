package com.dialex.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.model.PredefinedPersona
import com.dialex.theme.CcPalette
import com.dialex.theme.LocalCcColors

/**
 * Persona chip/badge rendered on the agent card when a persona is selected.
 *
 * Shows: icon · name (with "(Custom)" suffix if edited) · Edit button · Clear button.
 *
 * Tapping the badge itself (or the Edit button) opens the Persona Edit Sheet.
 * Tapping the × clears the persona and resets to default.
 */
@Composable
fun PersonaBadge(
    persona: PredefinedPersona?,
    customName: String? = null,
    isCustomised: Boolean = false,
    accentColor: Color,
    onEdit: () -> Unit,
    onChange: (() -> Unit)? = null,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    cc: CcPalette = LocalCcColors.current,
) {
    if (persona == null) {
        // Empty state — "+ Add a Role" prompt
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { onEdit() }
                .background(cc.panelAlt)
                .border(BorderStroke(1.dp, cc.border.copy(alpha = 0.5f)), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Outlined.Psychology,
                contentDescription = null,
                tint = cc.textMuted,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = "+ Add a Role",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = cc.textPrimary.copy(alpha = 0.8f)
            )
        }
        return
    }

    // Selected persona chip
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = accentColor.copy(alpha = if (cc.isDark) 0.12f else 0.07f),
        border = BorderStroke(0.75.dp, accentColor.copy(alpha = 0.4f)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Icon
            Surface(
                shape = CircleShape,
                color = accentColor.copy(alpha = 0.15f),
                modifier = Modifier.size(24.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    PersonaIconView(
                        icon = persona.icon,
                        category = persona.category,
                        size = 13.dp,
                        tint = accentColor
                    )
                }
            }

            // Name + Role
            Column(
                modifier = Modifier
                    .clickable { onEdit() },
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                val rawName = customName?.ifBlank { null } ?: persona.name
                val displayName = if (isCustomised && !rawName.endsWith("(Custom)")) "$rawName (Custom)" else rawName
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.5.sp
                    ),
                    color = cc.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val roleLabel = persona.role.ifBlank { persona.category }
                if (roleLabel.isNotBlank()) {
                    Text(
                        text = roleLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = cc.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Edit button (pencil)
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .clickable { onEdit() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = "Edit role",
                    tint = cc.textMuted,
                    modifier = Modifier.size(13.dp)
                )
            }

            // Change role button (library icon)
            if (onChange != null) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .clickable { onChange() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Change role",
                        tint = cc.textMuted,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }

            // Clear button (x)
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(cc.border.copy(alpha = 0.3f))
                    .clickable { onClear() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "Remove role",
                    tint = cc.textMuted,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}
