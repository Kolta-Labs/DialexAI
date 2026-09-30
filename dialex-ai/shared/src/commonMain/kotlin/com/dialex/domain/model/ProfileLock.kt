package com.dialex.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class LockMode {
    NONE,
    PIN,
    BIOMETRIC
}

@Serializable
sealed interface ProfileLockConfig {
    @Serializable
    data object None : ProfileLockConfig

    @Serializable
    data class CustomPin(
        val pinHash: String,
        val salt: String
    ) : ProfileLockConfig

    @Serializable
    data object DeviceBiometricOrPin : ProfileLockConfig
}

fun ProfileLockConfig.lockMode(): LockMode = when (this) {
    is ProfileLockConfig.None -> LockMode.NONE
    is ProfileLockConfig.CustomPin -> LockMode.PIN
    is ProfileLockConfig.DeviceBiometricOrPin -> LockMode.BIOMETRIC
}
