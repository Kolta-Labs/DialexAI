package com.dialex.android

import android.content.Context
import com.dialex.theme.ThemeMode

/** Mirrors desktop's `ThemePreferenceStore` exactly — see its doc comment. */
class ThemePreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences("theme", Context.MODE_PRIVATE)

    fun load(): ThemeMode {
        val name = prefs.getString("mode", null) ?: return ThemeMode.SYSTEM
        return runCatching { ThemeMode.valueOf(name) }.getOrDefault(ThemeMode.SYSTEM)
    }

    fun save(mode: ThemeMode) {
        prefs.edit().putString("mode", mode.name).apply()
    }
}
