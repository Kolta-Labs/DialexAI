package com.dialex.service.cliauth

import com.dialex.runner.resolvedShellPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

class DesktopCliProcessRunner : CliProcessRunner {
    override fun isSupported(): Boolean = true

    override suspend fun startInteractiveProcess(command: String): CliProcessSession =
        withContext(Dispatchers.IO) {
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
            val writer = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))

            object : CliProcessSession {
                override val isAlive: Boolean
                    get() = process.isAlive

                override val stdoutLines: Flow<String> = flow {
                    val reader = process.inputStream.bufferedReader(StandardCharsets.UTF_8)
                    try {
                        var line = reader.readLine()
                        while (line != null) {
                            emit(line)
                            line = reader.readLine()
                        }
                    } catch (e: Exception) {
                        // Stream closed
                    }
                }.flowOn(Dispatchers.IO)

                override suspend fun sendInput(text: String) {
                    withContext(Dispatchers.IO) {
                        try {
                            writer.write(text)
                            writer.flush()
                        } catch (e: Exception) {
                            // Writer closed or process exited
                        }
                    }
                }

                override suspend fun waitForExit(): Int =
                    withContext(Dispatchers.IO) {
                        process.waitFor()
                    }

                override fun cancel() {
                    try {
                        writer.close()
                    } catch (e: Exception) {}
                    process.destroyForcibly()
                }
            }
        }
}

actual fun createPlatformCliProcessRunner(): CliProcessRunner = DesktopCliProcessRunner()
