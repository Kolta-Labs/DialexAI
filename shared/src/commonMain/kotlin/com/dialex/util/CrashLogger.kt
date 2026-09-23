package com.dialex.util

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

// A crash log nobody can attach to a bug report is worse than not having one bother anyone —
// cap it so a spin loop of crashes can't quietly fill the disk.
private const val MAX_LOG_BYTES = 1_000_000L

/**
 * An uncaught exception on any thread otherwise kills the process with nothing but whatever
 * scrolled past in a terminal nobody's watching. This writes it to [logFile] first, then
 * chains to whatever handler (if any) was already installed — the JVM's default still runs,
 * so the process still exits; this is about leaving a trail, not recovering mid-crash.
 */
fun installCrashLogger(logFile: File) {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        runCatching {
            logFile.parentFile?.mkdirs()
            if (logFile.exists() && logFile.length() > MAX_LOG_BYTES) logFile.delete()
            val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }
            logFile.appendText("\n--- ${Instant.now()} on thread '${thread.name}' ---\n$trace")
        }
        previous?.uncaughtException(thread, throwable)
    }
}
