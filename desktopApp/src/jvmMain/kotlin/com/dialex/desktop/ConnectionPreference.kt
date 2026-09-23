package com.dialex.desktop

import java.io.File
import java.util.Properties

/** Which engine to talk to: this device's own (the default), or a remote one the user
 * pointed at explicitly. Never stores the remote password — only the URL and username, so a
 * remote connection still asks for the password each launch (there's no local-account
 * bootstrap trick for a login that isn't this device's own). */
sealed class ConnectionPreference {
    data object ThisDevice : ConnectionPreference()
    data class Remote(val url: String, val username: String) : ConnectionPreference()
}

object ConnectionPreferenceStore {
    private fun file() = File(EngineProcessManager.configDir(), "connection.properties")

    fun load(): ConnectionPreference? {
        val f = file()
        if (!f.exists()) return null
        val props = Properties().apply { f.inputStream().use { load(it) } }
        return when (props.getProperty("mode")) {
            "remote" -> ConnectionPreference.Remote(
                url = props.getProperty("url") ?: return null,
                username = props.getProperty("username") ?: return null,
            )
            else -> ConnectionPreference.ThisDevice
        }
    }

    fun save(preference: ConnectionPreference) {
        val f = file()
        f.parentFile.mkdirs()
        val props = Properties()
        when (preference) {
            is ConnectionPreference.ThisDevice -> props.setProperty("mode", "local")
            is ConnectionPreference.Remote -> {
                props.setProperty("mode", "remote")
                props.setProperty("url", preference.url)
                props.setProperty("username", preference.username)
            }
        }
        f.outputStream().use { props.store(it, null) }
    }

    fun clear() {
        file().delete()
    }
}
