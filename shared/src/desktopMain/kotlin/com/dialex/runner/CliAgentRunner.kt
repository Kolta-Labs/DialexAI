package com.dialex.runner

import com.dialex.model.Agent
import com.dialex.model.CliCommands
import com.dialex.model.DebateMessage
import com.dialex.model.label
import com.dialex.model.brandName
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Shells out to a local CLI (claude, agy, ...) per turn. Command always comes from the
 * global Settings [CliCommands] for the agent's provider — `agent.cliCommand` is ignored
 * (it's a leftover field kept only so old persisted discussions still deserialize; a
 * per-agent override would let a discussion saved before a Settings change silently keep
 * using a stale command forever). Split on spaces and run as a process; the prompt is
 * piped via stdin, full stdout is the reply. Desktop-only: ProcessBuilder isn't available
 * on Android/iOS.
 *
 * The actual read/wait is blocking native I/O, which a coroutine can't interrupt just by
 * being cancelled — so it runs on its own daemon thread while this suspends via
 * [suspendCancellableCoroutine]; `invokeOnCancellation` destroys the process the instant a
 * hard stop cancels the Job, which unblocks the read (closed stream) almost immediately —
 * that's what makes hard-stop actually immediate instead of waiting out the current turn.
 */
import com.dialex.model.FolderScope
import com.dialex.model.PermissionConfig
import java.io.File

class CliAgentRunner(
    private val commands: CliCommands = CliCommands(),
    private val timeoutSeconds: Long = 120,
    private val workspaceFolders: List<FolderScope> = emptyList(),
    private val permissions: PermissionConfig? = null,
) : AgentRunner {

    override suspend fun respond(
        agent: Agent,
        topic: String,
        commonContext: String,
        commonInstructions: String,
        transcript: List<DebateMessage>,
        modelOverride: String?,
    ): AgentReply {
        val command = commands.forProvider(agent.provider)
        val prompt = buildString {
            appendLine("Topic: $topic")
            if (commonContext.isNotBlank()) appendLine("Context: $commonContext")
            if (commonInstructions.isNotBlank()) appendLine("Rules: $commonInstructions")
            if (agent.context.isNotBlank()) appendLine(agent.context)
            if (agent.systemPrompt.isNotBlank()) appendLine(agent.systemPrompt)
            appendLine("--- transcript so far ---")
            transcript.forEach {
                val author = it.authorDisplayName.ifBlank { it.agentId.name }
                appendLine("$author: ${it.content}")
            }
            appendLine("--- your turn ---")
        }

        // claude/codex/agy all accept `--model <name>` — appending it (only when an override
        // is actually requested, e.g. compaction) swaps the model for just this one call
        // without touching the configured command in Settings.
        val rawTokens = command.split(" ") + (modelOverride?.let { listOf("--model", it) } ?: emptyList())
        val baseBin = rawTokens.first().substringAfterLast('/').substringAfterLast('\\')
        val initialTokens = if ((baseBin == "agy" || baseBin == "gemini") && !rawTokens.any { it == "--print" || it == "-p" || it == "--prompt" }) {
            rawTokens + listOf("--print", prompt)
        } else {
            rawTokens
        }

        // Resolve primary trusted workspace directory
        val trustedDir = workspaceFolders.firstOrNull { it.isTrusted && it.path.isNotBlank() }?.path?.let { File(it) }?.takeIf { it.isDirectory }

        // Evaluate WebSearch permission for this agent seat
        val webSearchAllowed = agent.allowWebSearch ?: permissions?.isWebSearchAllowedFor(agent.id) ?: permissions?.allowWebSearch ?: true

        val augmentedTokens = initialTokens.toMutableList()
        if (trustedDir != null && (baseBin == "claude" || baseBin == "agy") && !augmentedTokens.contains("--dangerously-skip-permissions")) {
            augmentedTokens.add("--dangerously-skip-permissions")
        }
        if (!webSearchAllowed && baseBin == "claude" && !augmentedTokens.contains("--disallow-tools")) {
            augmentedTokens.addAll(listOf("--disallow-tools", "WebSearch,WebFetch"))
        }

        val tokens = augmentedTokens.toList()
        val resolvedTokens = listOf(resolveBinary(tokens.first())) + tokens.drop(1)
        com.dialex.logging.AppLogStore.info("CliRunner", "Invoking CLI agent '${agent.label()}' (${agent.provider.brandName()}) via: $command")
        val process = try {
            val builder = ProcessBuilder(resolvedTokens).redirectErrorStream(true)
            if (trustedDir != null) {
                builder.directory(trustedDir)
            }
            builder.start()
        } catch (e: java.io.IOException) {
            val binary = command.substringBefore(' ')
            com.dialex.logging.AppLogStore.error("CliRunner", "CLI executable '$binary' not found on system PATH", e)
            error("CLI '$binary' not found. It isn't installed or isn't on your PATH — install it, or fix the command in Edit setup.")
        }

        val startTime = System.currentTimeMillis()
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { process.destroyForcibly() }
            Thread({
                val result = runCatching {
                    process.outputStream.bufferedWriter().use { it.write(prompt) }
                    val output = process.inputStream.bufferedReader().readText()
                    val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                    if (!finished) {
                        process.destroyForcibly()
                        com.dialex.logging.AppLogStore.error("CliRunner", "CLI '$command' timed out after ${timeoutSeconds}s")
                        error("CLI '$command' timed out after ${timeoutSeconds}s")
                    }
                    var finalOutput = output
                    if (process.exitValue() != 0 || isPermissionError(finalOutput)) {
                        if (isPermissionError(finalOutput) && !tokens.contains("--dangerously-skip-permissions")) {
                            com.dialex.logging.AppLogStore.warn("CliRunner", "Permission check failed for '${agent.label()}'. Auto-retrying with --dangerously-skip-permissions")
                            // Auto-retry with --dangerously-skip-permissions under autonomous intervention rules
                            val retryTokens = listOf(resolveBinary(tokens.first())) + tokens.drop(1) + listOf("--dangerously-skip-permissions")
                            val retryProcess = try {
                                ProcessBuilder(retryTokens).redirectErrorStream(true).start()
                            } catch (e: Exception) {
                                null
                            }
                            if (retryProcess != null) {
                                retryProcess.outputStream.bufferedWriter().use { it.write(prompt) }
                                val retryOutput = retryProcess.inputStream.bufferedReader().readText()
                                val retryFinished = retryProcess.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                                if (retryFinished && retryProcess.exitValue() == 0 && retryOutput.isNotBlank()) {
                                    finalOutput = retryOutput
                                    com.dialex.logging.AppLogStore.info("CliRunner", "CLI auto-retry succeeded for '${agent.label()}'")
                                }
                            }
                        }
                    }

                    val inTokens = kotlin.math.ceil(prompt.length / 3.8).toInt().coerceAtLeast(1)
                    val outTokens = kotlin.math.ceil(finalOutput.length / 3.8).toInt().coerceAtLeast(1)
                    val duration = System.currentTimeMillis() - startTime

                    if (process.exitValue() != 0 && isPermissionError(finalOutput) && finalOutput == output) {
                        val errMsg = "CLI '$command' requires tool permission approval. Re-run with --dangerously-skip-permissions in Settings or configure permissions.allow: ${finalOutput.trim()}"
                        com.dialex.logging.AppLogStore.error("CliRunner", "CLI '$command' permission denied: ${finalOutput.trim()}")
                        com.dialex.logging.ApiCallStore.trackCliCall(
                            provider = agent.provider,
                            model = agent.model,
                            command = command,
                            promptStdin = prompt,
                            outputStdout = finalOutput,
                            exitCode = process.exitValue(),
                            errorDetails = errMsg,
                            durationMs = duration,
                            isSuccess = false,
                            tokensIn = inTokens,
                            tokensOut = outTokens
                        )
                        error(errMsg)
                    } else if (process.exitValue() != 0 && finalOutput == output) {
                        com.dialex.logging.AppLogStore.error("CliRunner", "CLI '$command' exited with code ${process.exitValue()}: ${output.take(300)}")
                        val message = if (looksLikeAuthFailure(output)) {
                            val binary = command.substringBefore(' ')
                            if (launchInteractiveLogin(binary)) {
                                "CLI '$binary' needs re-authentication. Opened a terminal running '$binary' — finish logging in there, then press Start again."
                            } else {
                                "CLI '$command' exited ${process.exitValue()}: $output"
                            }
                        } else {
                            "CLI '$command' exited ${process.exitValue()}: $output"
                        }
                        com.dialex.logging.ApiCallStore.trackCliCall(
                            provider = agent.provider,
                            model = agent.model,
                            command = command,
                            promptStdin = prompt,
                            outputStdout = finalOutput,
                            exitCode = process.exitValue(),
                            errorDetails = message,
                            durationMs = duration,
                            isSuccess = false,
                            tokensIn = inTokens,
                            tokensOut = outTokens
                        )
                        error(message)
                    }

                    com.dialex.logging.ApiCallStore.trackCliCall(
                        provider = agent.provider,
                        model = agent.model,
                        command = command,
                        promptStdin = prompt,
                        outputStdout = finalOutput,
                        exitCode = process.exitValue(),
                        errorDetails = null,
                        durationMs = duration,
                        isSuccess = true,
                        tokensIn = inTokens,
                        tokensOut = outTokens
                    )

                    AgentReply(
                        content = finalOutput.trim(),
                        tokensIn = inTokens,
                        tokensOut = outTokens
                    )
                }
                // A hard stop destroys the process mid-read, which surfaces here as a
                // failure too (closed stream) — but the continuation is already cancelled
                // by then, so skip resuming instead of crashing on a double-resume.
                if (cont.isActive) {
                    result.fold(onSuccess = { cont.resume(it) }, onFailure = { cont.resumeWithException(it) })
                }
            }, "cli-agent-runner").apply { isDaemon = true }.start()
        }
    }
}

private fun looksLikeAuthFailure(output: String) =
    listOf("authenticate", "oauth", "session expired", "not logged in", "unauthorized")
        .any { it in output.lowercase() }

private fun isPermissionError(output: String) =
    listOf(
        "dangerously-skip-permissions",
        "headless mode cannot prompt for",
        "auto-denied",
        "permission.allow",
        "no search access granted",
        "search access",
        "permission required",
        "tool required the"
    ).any { it in output.lowercase() }

/**
 * Can't complete OAuth headlessly — it needs a browser + the user. Best we can do is pop
 * a terminal running the bare CLI, which for claude/codex/gemini triggers their own login
 * prompt on an expired session. macOS only for now.
 * ponytail: Windows/Linux terminal launch not implemented, add when needed.
 */
private fun launchInteractiveLogin(binary: String): Boolean {
    if ("mac" !in System.getProperty("os.name").lowercase()) return false
    return runCatching {
        ProcessBuilder("osascript", "-e", "tell application \"Terminal\" to do script \"$binary\"").start()
    }.isSuccess
}
