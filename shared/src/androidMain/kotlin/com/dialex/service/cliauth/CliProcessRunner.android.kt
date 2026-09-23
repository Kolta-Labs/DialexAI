package com.dialex.service.cliauth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class AndroidCliProcessRunner : CliProcessRunner {
    override fun isSupported(): Boolean = false

    override suspend fun startInteractiveProcess(command: String): CliProcessSession {
        error("CLI execution is not supported on Android sandbox")
    }
}

actual fun createPlatformCliProcessRunner(): CliProcessRunner = AndroidCliProcessRunner()
