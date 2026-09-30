package com.dialex.presentation.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.dialex.domain.model.LockMode
import com.dialex.ui.AestheticRadioButton
import com.dialex.domain.model.ProfileLockConfig
import com.dialex.domain.model.lockMode
import com.dialex.theme.LocalCcColors
import com.dialex.ui.GradientButton
import com.dialex.util.CryptoUtils

@Composable
fun ProfileLockSetupDialog(
    currentConfig: ProfileLockConfig,
    onSaveLockConfig: (ProfileLockConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val cc = LocalCcColors.current
    var selectedMode by remember { mutableStateOf(currentConfig.lockMode()) }
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = cc.panel,
            border = androidx.compose.foundation.BorderStroke(1.dp, cc.border),
            modifier = Modifier.width(420.dp)
        ) {
            Column(Modifier.padding(24.dp)) {
                Text(
                    text = "Profile Access Protection",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = cc.textPrimary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Configure an authentication challenge to lock this profile. When locked, debate transcripts, projects, and saved LLM API keys cannot be accessed without authenticating.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cc.textMuted
                )

                Spacer(Modifier.height(20.dp))

                // Option 1: None
                LockOptionTile(
                    icon = Icons.Default.LockOpen,
                    title = "No Lock",
                    subtitle = "Open access without PIN or biometric challenge",
                    selected = selectedMode == LockMode.NONE,
                    onClick = {
                        selectedMode = LockMode.NONE
                        error = null
                    }
                )

                Spacer(Modifier.height(10.dp))

                // Option 2: Custom PIN
                LockOptionTile(
                    icon = Icons.Default.Lock,
                    title = "Custom PIN",
                    subtitle = "Require a 4 to 8 digit PIN to unlock this profile",
                    selected = selectedMode == LockMode.PIN,
                    onClick = {
                        selectedMode = LockMode.PIN
                        error = null
                    }
                )

                Spacer(Modifier.height(10.dp))

                // Option 3: Device Biometrics
                LockOptionTile(
                    icon = Icons.Default.Fingerprint,
                    title = "Device Biometrics / PIN",
                    subtitle = "Use your system fingerprint, face unlock, or OS password",
                    selected = selectedMode == LockMode.BIOMETRIC,
                    onClick = {
                        selectedMode = LockMode.BIOMETRIC
                        error = null
                    }
                )

                if (selectedMode == LockMode.PIN) {
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = pin,
                        onValueChange = {
                            if (it.length <= 8 && it.all { char -> char.isDigit() }) {
                                pin = it
                                error = null
                            }
                        },
                        label = { Text("New PIN (4-8 digits)") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = confirmPin,
                        onValueChange = {
                            if (it.length <= 8 && it.all { char -> char.isDigit() }) {
                                confirmPin = it
                                error = null
                            }
                        },
                        label = { Text("Confirm PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp
                    )
                }

                Spacer(Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = cc.textMuted)
                    }

                    Spacer(Modifier.width(12.dp))

                    GradientButton(
                        text = "Save",
                        onClick = {
                            when (selectedMode) {
                                LockMode.NONE -> {
                                    onSaveLockConfig(ProfileLockConfig.None)
                                    onDismiss()
                                }
                                LockMode.BIOMETRIC -> {
                                    onSaveLockConfig(ProfileLockConfig.DeviceBiometricOrPin)
                                    onDismiss()
                                }
                                LockMode.PIN -> {
                                    if (pin.length < 4) {
                                        error = "PIN must be at least 4 digits."
                                    } else if (pin != confirmPin) {
                                        error = "PINs do not match."
                                    } else {
                                        val salt = CryptoUtils.generateSalt()
                                        val hash = CryptoUtils.hashPin(pin, salt)
                                        onSaveLockConfig(ProfileLockConfig.CustomPin(pinHash = hash, salt = salt))
                                        onDismiss()
                                    }
                                }
                            }
                        },
                        height = 30.dp
                    )
                }
            }
        }
    }
}

@Composable
private fun LockOptionTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val cc = LocalCcColors.current
    val bg = if (selected) cc.panelAlt else cc.panel
    val border = if (selected) cc.border else cc.border.copy(alpha = 0.4f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = cc.textMuted,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = if (selected) cc.textPrimary else cc.textPrimary.copy(alpha = 0.85f)
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                color = cc.textMuted
            )
        }
        AestheticRadioButton(
            selected = selected,
            onClick = onClick,
            size = 18.dp
        )
    }
}
