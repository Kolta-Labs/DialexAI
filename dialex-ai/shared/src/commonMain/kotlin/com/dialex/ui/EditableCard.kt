package com.dialex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.dialex.theme.LocalCcColors

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp

/**
 * Shows `title` + a truncated preview of `value` as a card; clicking opens a dialog with
 * a full-size text area to edit it. Used for the long free-text fields (context, prompts)
 * so the surrounding form stays scannable instead of a wall of open textareas.
 */
@Composable
fun EditableCard(
    title: String,
    value: String,
    placeholder: String,
    required: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    val cc = LocalCcColors.current
    var dialogOpen by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(cc.panel)
            .border(BorderStroke(1.dp, cc.border.copy(alpha = 0.6f)), RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Row {
            Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            if (required) Text(" *", style = MaterialTheme.typography.labelMedium, color = cc.accent, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            value.ifBlank { placeholder }.take(120).let { if (value.length > 120) "$it…" else it },
            style = MaterialTheme.typography.bodySmall,
            color = if (value.isBlank()) cc.textMuted else cc.textPrimary,
            maxLines = 2,
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(
                onClick = { dialogOpen = true },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = cc.textPrimary),
                border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.8f))
            ) {
                Text(
                    if (value.isBlank()) "+ Add" else "Edit",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium, fontSize = 12.5.sp)
                )
            }
        }
    }

    if (dialogOpen) {
        EditDialog(title, value, placeholder, onDismiss = { dialogOpen = false }, onSave = { onValueChange(it); dialogOpen = false })
    }
}

@Composable
private fun EditDialog(
    title: String,
    initialValue: String,
    placeholder: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val cc = LocalCcColors.current
    var text by remember { mutableStateOf(initialValue) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(480.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(cc.panel)
                .border(BorderStroke(1.dp, cc.border.copy(alpha = 0.6f)), RoundedCornerShape(12.dp))
                .padding(20.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
                textStyle = MaterialTheme.typography.bodyMedium,
                minLines = 6,
                maxLines = 12,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = cc.textMuted)
                ) { Text("Cancel", style = MaterialTheme.typography.labelMedium) }
                Spacer(Modifier.width(8.dp))
                GradientButton(
                    text = "Save",
                    onClick = { onSave(text) },
                    height = 34.dp,
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)
                )
            }
        }
    }
}
