package com.dialex.desktop

import com.dialex.theme.ThemeMode
import java.io.File

/** Persists the user's Appearance → Theme choice across launches — same directory as
 * `ConnectionPreference`, different tiny file, same reasoning for keeping it its own file
 * rather than piggybacking on the engine's own settings (this is purely a client-side
 * display preference, not debate data; it shouldn't round-trip through the engine or be
 * shared between a desktop and a phone pointed at the same one). */
object ThemePreferenceStore {
    private fun file() = File(EngineProcessManager.configDir(), "theme.txt")

    fun load(): ThemeMode {
        val text = runCatching { file().readText().trim() }.getOrNull() ?: return ThemeMode.SYSTEM
        return runCatching { ThemeMode.valueOf(text) }.getOrDefault(ThemeMode.SYSTEM)
    }

    fun save(mode: ThemeMode) {
        val f = file()
        f.parentFile.mkdirs()
        f.writeText(mode.name)
    }
}
