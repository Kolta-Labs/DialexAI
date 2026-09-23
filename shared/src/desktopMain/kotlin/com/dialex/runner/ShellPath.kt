package com.dialex.runner

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The PATH a plain ProcessBuilder inherits is whatever launched the JVM (Gradle daemon,
 * Finder, ...) — it does NOT include entries a shell rc file adds (nvm, pyenv, a local
 * bin dir), because those only get sourced by an interactive login shell. That's why a
 * CLI the user can run fine in Terminal comes back "not found" here.
 *
 * Fix: ask the user's actual login shell for its PATH once, cache it, and search it
 * ourselves to resolve a bare binary name to an absolute path. (Setting
 * `ProcessBuilder.environment()["PATH"]` is NOT enough on its own — the JDK resolves the
 * executable's location using the *JVM's original* PATH before the child even starts, so
 * a bare "agy" still fails to launch even once the child's env PATH is correct. Handing
 * ProcessBuilder an absolute path sidesteps that lookup entirely.)
 */
val resolvedShellPath: String by lazy {
    val shellPath = runCatching {
        val shell = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/zsh"
        val process = ProcessBuilder(shell, "-ilc", "echo -n \$PATH")
            .redirectErrorStream(false)
            .start()
        val out = process.inputStream.bufferedReader().readText().trim()
        process.waitFor(5, TimeUnit.SECONDS)
        out.takeIf { it.isNotBlank() && "/" in it }
    }.getOrNull() ?: System.getenv("PATH").orEmpty()

    val home = System.getProperty("user.home") ?: ""
    val commonDirs = mutableListOf(
        "$home/.local/bin",
        "$home/.gemini/antigravity/bin",
        "$home/Library/Application Support/Antigravity/bin",
        "/opt/homebrew/bin",
        "/usr/local/bin",
        "$home/.cargo/bin",
        "$home/.npm-global/bin"
    )
    val nvmNode = File("$home/.nvm/versions/node")
    if (nvmNode.isDirectory) {
        nvmNode.listFiles()?.filter { it.isDirectory }?.forEach {
            commonDirs.add("${it.absolutePath}/bin")
        }
    }
    (shellPath.split(":") + commonDirs).distinct().filter { it.isNotBlank() }.joinToString(":")
}

/** Resolves a bare binary name (e.g. "agy") to its absolute path by searching
 * [resolvedShellPath], falling back to alias checks (e.g. agy <-> antigravity), then to the name unchanged. */
fun resolveBinary(name: String): String {
    if ("/" in name) return name
    val namesToTry = when (name) {
        "antigravity", "gemini" -> listOf(name, "agy")
        "agy" -> listOf("agy", "antigravity", "gemini")
        "claude" -> listOf("claude", "claude-code")
        else -> listOf(name)
    }
    val paths = resolvedShellPath.split(":")
    for (n in namesToTry) {
        val found = paths.asSequence()
            .filter { it.isNotBlank() }
            .map { File(it, n) }
            .firstOrNull { it.isFile && it.canExecute() }
            ?.absolutePath
        if (found != null) return found
    }
    return name
}
