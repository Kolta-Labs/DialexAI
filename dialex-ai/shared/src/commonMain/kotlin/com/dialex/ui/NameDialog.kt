package com.dialex.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.dialex.theme.LocalAppColors

/**
 * Consistent, crash-free single input modal using standard Compose Dialog.
 */
@Composable
fun NameDialog(
    title: String,
    confirmLabel: String = "Create",
    placeholder: String = "Name",
    initialValue: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val cc = LocalAppColors.current
    var text by remember(initialValue) { mutableStateOf(initialValue) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(cc.panel)
                .border(BorderStroke(1.dp, cc.border.copy(alpha = 0.5f)), RoundedCornerShape(12.dp))
                .padding(20.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium, fontSize = 13.sp),
                color = cc.textPrimary
            )

            Spacer(Modifier.height(14.dp))

            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = cc.textPrimary, fontSize = 12.sp),
                cursorBrush = SolidColor(cc.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(cc.panelAlt)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                decorationBox = { innerTextField ->
                    if (text.isEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = cc.textMuted.copy(alpha = 0.6f)
                        )
                    }
                    innerTextField()
                }
            )

            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp))
                }
                Spacer(Modifier.width(8.dp))
                GradientButton(
                    text = confirmLabel,
                    onClick = {
                        if (text.isNotBlank()) {
                            onConfirm(text.trim())
                        }
                    },
                    enabled = text.isNotBlank()
                )
            }
        }
    }
}
