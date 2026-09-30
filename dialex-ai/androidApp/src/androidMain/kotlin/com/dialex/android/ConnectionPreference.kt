package com.dialex.android

import android.content.Context

/** Which engine to talk to: this device's own (the default), or a remote one the user
 * pointed at explicitly. Mirrors desktop's `ConnectionPreference`/`ConnectionPreferenceStore`
 * exactly (same "never store the remote password" reasoning) — see its doc comment. */
sealed class ConnectionPreference {
    data object ThisDevice : ConnectionPreference()
    data class Remote(val url: String, val username: String) : ConnectionPreference()
}

class ConnectionPreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE)

    fun load(): ConnectionPreference? {
        return when (prefs.getString("mode", null)) {
            "remote" -> ConnectionPreference.Remote(
                url = prefs.getString("url", null) ?: return null,
                username = prefs.getString("username", null) ?: return null,
            )
            "local" -> ConnectionPreference.ThisDevice
            else -> null
        }
    }

    fun save(preference: ConnectionPreference) {
        prefs.edit().apply {
            when (preference) {
                is ConnectionPreference.ThisDevice -> putString("mode", "local")
                is ConnectionPreference.Remote -> {
                    putString("mode", "remote")
                    putString("url", preference.url)
                    putString("username", preference.username)
                }
            }
        }.apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
