package com.dialex.presentation.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.dialex.domain.model.ConnectionProfile
import com.dialex.domain.model.ProfileLockConfig
import com.dialex.theme.LocalCcColors
import com.dialex.ui.GradientButton

@Composable
fun ProfileLockDialog(
    profile: ConnectionProfile,
    onUnlockWithPin: (String) -> Boolean,
    onUnlockWithBiometric: () -> Unit,
    onSwitchProfile: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cc = LocalCcColors.current
    var pinInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val isBiometric = profile.lockConfig is ProfileLockConfig.DeviceBiometricOrPin

    Dialog(onDismissRequest = { /* Modal lock - cannot dismiss without unlocking */ }) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = cc.panel,
            border = androidx.compose.foundation.BorderStroke(1.dp, cc.border),
            modifier = modifier.width(360.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(cc.panelAlt),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Locked Profile",
                        tint = cc.textMuted,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = "${profile.name} is Locked",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = cc.textPrimary
                )

                Spacer(Modifier.height(6.dp))

                Text(
                    text = if (isBiometric) "Authenticate with your device biometric or PIN to continue"
                    else "Enter your profile PIN to unlock debate sessions and API keys",
                    style = MaterialTheme.typography.bodySmall,
                    color = cc.textMuted,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Spacer(Modifier.height(20.dp))

                if (!isBiometric) {
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = {
                            pinInput = it
                            errorMessage = null
                        },
                        label = { Text("PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (onUnlockWithPin(pinInput)) {
                                    errorMessage = null
                                } else {
                                    errorMessage = "Incorrect PIN. Try again."
                                }
                            }
                        ),
                        singleLine = true,
                        isError = errorMessage != null,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (errorMessage != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = errorMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp
                        )
                    }

                    Spacer(Modifier.height(20.dp))

                    GradientButton(
                        text = "Unlock Profile",
                        onClick = {
                            if (onUnlockWithPin(pinInput)) {
                                errorMessage = null
                            } else {
                                errorMessage = "Incorrect PIN. Try again."
                            }
                        },
                        enabled = pinInput.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        height = 40.dp
                    )
                } else {
                    GradientButton(
                        text = "Unlock with Biometrics",
                        onClick = onUnlockWithBiometric,
                        modifier = Modifier.fillMaxWidth(),
                        height = 40.dp
                    )
                }

                Spacer(Modifier.height(12.dp))

                TextButton(
                    onClick = onSwitchProfile,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Switch Profile", color = cc.textMuted, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
