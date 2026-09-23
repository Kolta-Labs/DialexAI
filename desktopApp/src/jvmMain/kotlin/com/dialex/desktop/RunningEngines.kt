package com.dialex.desktop

import java.io.File

/** One `roundtable` (or `libroundtable.so` — same binary, Android-built) process visible to
 * this OS user, whether this app instance spawned it or not. */
data class RunningEngine(
    val pid: Long,
    val commandLine: String,
    /** True for the one process (if any) this exact app instance itself spawned via
     * [EngineProcessManager] — informational only, not a safety gate; every row is equally
     * killable, this just tells the user which one is "theirs" right now. */
    val isThisApp: Boolean,
)

/** Desktop-only: finds and kills `roundtable` engine processes visible to this OS user, for
 * Settings → Servers. Exists because [EngineProcessManager] only ever tracks the *one*
 * process it itself spawned this run — a crash, a force-quit, or just running the app twice
 * can leave others behind with nothing in this app pointing at them at all. Backed by
 * `java.lang.ProcessHandle` (JVM only, since Java 9) — there's no equivalent API on Android,
 * and no orphan concept to speak of there anyway (a phone has exactly one engine, tied to
 * this app's own process lifecycle, not several independently-launched ones on a shared
 * machine). */
object RunningEngines {
    /** Best-effort: a process whose owner isn't this OS user, or one that exited between the
     * enumeration and reading its info, is silently skipped rather than shown as a mystery
     * row or crashing the Settings screen. */
    fun list(): List<RunningEngine> {
        val ownPid = EngineProcessManager.spawnedPid()
        return ProcessHandle.allProcesses()
            .filter { handle ->
                val command = handle.info().command().orElse(null) ?: return@filter false
                val name = File(command).name
                name == "dialex" || name == "libdialex.so" || name == "roundtable" || name == "libroundtable.so"
            }
            .map { handle ->
                val info = handle.info()
                val commandLine = info.commandLine().orElseGet {
                    (info.command().orElse("?") + " " + info.arguments().orElse(emptyArray()).joinToString(" ")).trim()
                }
                RunningEngine(pid = handle.pid(), commandLine = commandLine, isThisApp = handle.pid() == ownPid)
            }
            .sorted(compareBy { it.pid })
            .toList()
    }

    /** Politely asks the process to exit (SIGTERM-equivalent); [forcibly] skips straight to
     * SIGKILL-equivalent for one that won't. Returns false if the process was already gone by
     * the time this ran — not an error, just nothing left to do. */
    fun kill(pid: Long, forcibly: Boolean = false): Boolean {
        val handle = ProcessHandle.of(pid).orElse(null) ?: return false
        return if (forcibly) handle.destroyForcibly() else handle.destroy()
    }
}
