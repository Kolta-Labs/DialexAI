package com.dialex.service

actual fun isPlatformCliSupported(): Boolean = false

actual fun checkPlatformCliLogins(): Map<String, Boolean> = emptyMap()

actual fun checkPlatformCliAvailability(): Map<String, Boolean> = emptyMap()

actual fun launchCliLoginTerminal(command: String): Result<Unit> =
    Result.failure(UnsupportedOperationException("CLI execution is not supported on Android"))

actual suspend fun runCliInstallCommand(command: String, onOutputLine: (String) -> Unit): Result<Unit> =
    Result.failure(UnsupportedOperationException("CLI execution is not supported on Android"))
