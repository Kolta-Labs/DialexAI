package com.dialex.service

import com.dialex.runner.checkCliAvailability
import com.dialex.runner.checkCliLoginStatus
import com.dialex.runner.resolvedShellPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual fun isPlatformCliSupported(): Boolean = true

actual fun checkPlatformCliLogins(): Map<String, Boolean> = checkCliLoginStatus()

actual fun checkPlatformCliAvailability(): Map<String, Boolean> = checkCliAvailability()

actual fun launchCliLoginTerminal(command: String): Result<Unit> {
    val os = System.getProperty("os.name").lowercase()
    return runCatching {
        when {
            "mac" in os -> {
                val fullCmd = "export PATH=\"$resolvedShellPath:\$PATH\"; $command"
                val escaped = fullCmd.replace("\\", "\\\\").replace("\"", "\\\"")
                ProcessBuilder(
                    "osascript",
                    "-e", "tell application \"Terminal\" to activate",
                    "-e", "tell application \"Terminal\" to do script \"$escaped\""
                ).start()
            }
            "win" in os -> {
                ProcessBuilder("cmd.exe", "/c", "start", "cmd.exe", "/k", command).start()
            }
            else -> {
                val terminals = listOf("x-terminal-emulator", "gnome-terminal", "konsole", "xfce4-terminal", "alacritty", "kitty")
                val launched = terminals.any { term ->
                    runCatching {
                        ProcessBuilder(term, "-e", command).start()
                        true
                    }.getOrDefault(false)
                }
                if (!launched) {
                    error("No compatible terminal emulator found to launch login")
                }
            }
        }
        Unit
    }
}

actual suspend fun runCliInstallCommand(command: String, onOutputLine: (String) -> Unit): Result<Unit> =
    withContext(Dispatchers.IO) {
        runCatching {
            val shell = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/zsh"
            val os = System.getProperty("os.name").lowercase()
            val pb = if ("win" in os) {
                ProcessBuilder("cmd.exe", "/c", command)
            } else {
                ProcessBuilder(shell, "-ilc", command)
            }
            pb.environment()["PATH"] = resolvedShellPath
            pb.redirectErrorStream(true)
            val process = pb.start()
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    onOutputLine(line)
                }
            }
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                error("Installation exited with code $exitCode")
            }
        }
    }
