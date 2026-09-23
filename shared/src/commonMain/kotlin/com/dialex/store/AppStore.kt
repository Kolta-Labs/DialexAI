package com.dialex.store

import com.dialex.model.ApiKeys
import com.dialex.model.AppState
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private fun File.restrictToOwner() {
    // POSIX-only (no-op, harmlessly, on Windows) — best-effort, not the whole security story.
    runCatching {
        Files.setPosixFilePermissions(toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
    }
}

/**
 * Encrypts just the API keys before they touch disk. AES-256-GCM with a random per-install
 * key kept in a sibling file — a copy of state.json alone (backup, sync, screen-share) no
 * longer hands over live API keys, only this separate key file does.
 * ponytail: a software key on disk, not an OS keychain/hardware-backed secret — a real step
 * up from plaintext, not the final word; move to Keychain/EncryptedSharedPreferences if that
 * gap starts to matter.
 */
private class SecretBox(keyFile: File) {
    private val key = run {
        keyFile.parentFile?.mkdirs()
        if (!keyFile.exists()) {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            keyFile.writeBytes(bytes)
            keyFile.restrictToOwner()
        }
        SecretKeySpec(keyFile.readBytes(), "AES")
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    // Anything that doesn't decrypt cleanly (most notably: a plaintext key from before this
    // encryption existed) is returned as-is rather than dropped — it still works as a key,
    // and gets encrypted properly the next time save() runs. Never lose a user's key over a
    // migration.
    fun decrypt(blob: String): String {
        if (blob.isEmpty()) return ""
        return runCatching {
            val bytes = Base64.getDecoder().decode(blob)
            require(bytes.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }.getOrDefault(blob)
    }
}

// Named args deliberately, not positional — ApiKeys has grown fields more than once and a
// positional call here would silently leave any new field un-encrypted (and un-decrypted).
private fun ApiKeys.encrypted(box: SecretBox) = ApiKeys(
    anthropic = box.encrypt(anthropic),
    openai = box.encrypt(openai),
    gemini = box.encrypt(gemini),
    grok = box.encrypt(grok),
    deepseek = box.encrypt(deepseek),
    mistral = box.encrypt(mistral),
)

private fun ApiKeys.decrypted(box: SecretBox) = ApiKeys(
    anthropic = box.decrypt(anthropic),
    openai = box.decrypt(openai),
    gemini = box.decrypt(gemini),
    grok = box.decrypt(grok),
    deepseek = box.decrypt(deepseek),
    mistral = box.decrypt(mistral),
)

/**
 * Reads/writes the whole app state as one JSON file. Fine at this scale (a handful of
 * projects/discussions on one machine) — swap for real storage if that changes.
 * ponytail: java.io.File works on desktop+Android (both JVM) but not iOS; move behind
 * expect/actual if an iOS target is ever added.
 */
class AppStore(private val file: File) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val secrets = SecretBox(File(file.parentFile, ".${file.name}.key"))

    fun load(): AppState {
        if (!file.exists()) return AppState()
        val loaded = runCatching { json.decodeFromString<AppState>(file.readText()) }.getOrNull()
            ?: loadFromBackup()
            ?: AppState()
        return loaded.copy(apiKeys = loaded.apiKeys.decrypted(secrets))
    }

    private fun loadFromBackup(): AppState? {
        for (n in 1..BACKUP_COUNT) {
            val text = runCatching { backupFile(n).readText() }.getOrNull() ?: continue
            runCatching { json.decodeFromString<AppState>(text) }.getOrNull()?.let { return it }
        }
        return null
    }

    // Write-to-temp-then-rename is atomic on both platforms' filesystems — a crash, kill, or
    // full disk mid-write leaves the temp file damaged, never the file load() actually reads.
    // Rotating backups cover the other failure mode: a *clean* write of a state that turns
    // out to be broken (e.g. this process's own bug) — recoverable from one save back.
    fun save(state: AppState) {
        file.parentFile?.mkdirs()
        val text = json.encodeToString(state.copy(apiKeys = state.apiKeys.encrypted(secrets)))
        rotateBackups()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        tmp.restrictToOwner()
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        file.restrictToOwner()
    }

    private fun backupFile(n: Int) = File(file.parentFile, "${file.name}.bak$n")

    private fun rotateBackups() {
        if (!file.exists()) return
        for (n in BACKUP_COUNT downTo 2) backupFile(n - 1).takeIf { it.exists() }?.copyTo(backupFile(n), overwrite = true)
        file.copyTo(backupFile(1), overwrite = true)
    }

    private companion object {
        const val BACKUP_COUNT = 2
    }
}
