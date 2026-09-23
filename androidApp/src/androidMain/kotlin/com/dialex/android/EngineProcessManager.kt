package com.dialex.android

import android.content.Context
import com.dialex.engine.EngineClient
import com.dialex.engine.EngineException
import kotlinx.coroutines.delay
import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * The on-device counterpart to desktop's `EngineProcessManager` — same idea, same account-
 * bootstrap trick (a random password generated once, stored 0600 in the app's own sandboxed
 * files dir, no login screen), adapted to how Android actually lets an app run a bundled
 * native binary: `libroundtable.so` under `applicationInfo.nativeLibraryDir` (see
 * `src/androidMain/jniLibs/arm64-v8a/libroundtable.so` and the manifest's
 * `android:extractNativeLibs="true"` — that's what makes Android's installer extract it with
 * the execute bit set rather than leaving it zipped inside the APK). Runs as a genuine
 * subprocess of the app, on 127.0.0.1 only, never reachable off-device.
 *
 * A *remote* engine (self-hosted, or a desktop's) is a separate, not-yet-built connection
 * flow — this only covers "run the engine on this phone".
 */
class EngineProcessManager(private val context: Context) {
    private val port = 7890
    private val baseUrl = "http://127.0.0.1:$port"
    private val username = "mobile"

    class BootstrapException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private var spawnedProcess: Process? = null

    suspend fun start(): EngineClient {
        val configDir = File(context.filesDir, "engine")
        configDir.mkdirs()

        if (!isHealthy()) {
            val binary = File(context.applicationInfo.nativeLibraryDir, "libdialex.so").takeIf { it.exists() }
                ?: File(context.applicationInfo.nativeLibraryDir, "libroundtable.so")
            if (!binary.exists()) throw BootstrapException("Engine binary not found at ${binary.absolutePath} — was it built for this ABI?")
            spawnedProcess = ProcessBuilder(binary.absolutePath, "serve", "--host", "127.0.0.1:$port", "--dir", configDir.absolutePath)
                .redirectOutput(File(configDir, "engine.log"))
                .redirectErrorStream(true)
                .start()
            waitForHealth(configDir)
        }

        val password = loadOrCreatePassword(configDir)
        val client = EngineClient(baseUrl)
        try {
            client.login(username, password)
        } catch (e: EngineException) {
            if (e.status != 401) throw e
            val binary = File(context.applicationInfo.nativeLibraryDir, "libdialex.so").takeIf { it.exists() }
                ?: File(context.applicationInfo.nativeLibraryDir, "libroundtable.so")
            createUser(binary, configDir, password)
            client.login(username, password)
        }
        return client
    }

    fun stop() {
        spawnedProcess?.destroy()
        spawnedProcess = null
    }

    private suspend fun isHealthy(): Boolean = runCatching { EngineClient(baseUrl).health() }.isSuccess

    private suspend fun waitForHealth(configDir: File) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (isHealthy()) return
            delay(150)
        }
        throw BootstrapException("Engine did not become healthy on $baseUrl within 10s — check ${File(configDir, "engine.log")}")
    }

    private fun createUser(binary: File, configDir: File, password: String) {
        val process = ProcessBuilder(binary.absolutePath, "users", "add", "--dir", configDir.absolutePath, username)
            .redirectErrorStream(true)
            .start()
        process.outputStream.bufferedWriter().use { it.write("$password\n") }
        val log = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) throw BootstrapException("Could not create the mobile account:\n$log")
    }

    private fun loadOrCreatePassword(configDir: File): String {
        val file = File(configDir, ".mobile-credentials")
        if (file.exists()) return file.readText().trim()
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val password = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        file.writeText(password)
        // Android already sandboxes filesDir to this app's UID (MODE_PRIVATE-equivalent by
        // default), but belt-and-suspenders costs nothing here.
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
        return password
    }
}
