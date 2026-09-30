package com.dialex.data.datasource

import com.dialex.data.db.SqlDatabase
import com.dialex.domain.model.ApiKeyStatus
import com.dialex.domain.model.ProfileApiKeysStatus
import com.dialex.model.Provider
import com.dialex.util.CryptoUtils
import java.io.File
import java.util.Base64

/**
 * Handles encrypted storage of LLM API keys at rest.
 * Keys are encrypted via AES-256-GCM and never returned in plaintext to the UI.
 */
class SecureKeyDataSource(
    private val db: SqlDatabase,
    private val keyDir: File = File(System.getProperty("user.home", "."), ".dialex")
) {
    private val masterKey: ByteArray by lazy { loadOrCreateMasterKey() }

    private fun loadOrCreateMasterKey(): ByteArray {
        keyDir.mkdirs()
        val keyFile = File(keyDir, ".vault.key")
        return if (keyFile.exists()) {
            val encoded = keyFile.readText().trim()
            Base64.getDecoder().decode(encoded)
        } else {
            val key = CryptoUtils.generateRandomKey(32)
            keyFile.writeText(Base64.getEncoder().encodeToString(key))
            key
        }
    }

    fun storeKey(profileId: String, provider: Provider, plaintextKey: String) {
        if (plaintextKey.isBlank()) {
            removeKey(profileId, provider)
            return
        }

        val encrypted = CryptoUtils.encryptAesGcm(plaintextKey.trim(), masterKey)
        val now = System.currentTimeMillis()

        val sql = """
            INSERT OR REPLACE INTO api_keys_secure (
                profile_id, provider, ciphertext, iv, updated_at
            ) VALUES (?, ?, ?, ?, ?)
        """.trimIndent()

        db.exec(sql, listOf(profileId, provider.name, encrypted.ciphertext, encrypted.iv, now))
    }

    fun removeKey(profileId: String, provider: Provider) {
        val sql = "DELETE FROM api_keys_secure WHERE profile_id = ? AND provider = ?"
        db.exec(sql, listOf(profileId, provider.name))
    }

    fun isKeyConfigured(profileId: String, provider: Provider): Boolean {
        val sql = "SELECT COUNT(*) FROM api_keys_secure WHERE profile_id = ? AND provider = ?"
        val count = db.querySingle(sql, listOf(profileId, provider.name)) { it.getInt(0) } ?: 0
        return count > 0
    }

    fun getApiKeyStatuses(profileId: String): ProfileApiKeysStatus {
        val sql = "SELECT provider FROM api_keys_secure WHERE profile_id = ?"
        val configuredProviders = db.query(sql, listOf(profileId)) { cursor ->
            cursor.getString(0)?.let { name -> runCatching { Provider.valueOf(name) }.getOrNull() }
        }.filterNotNull().toSet()

        val statuses = Provider.entries.associateWith { provider ->
            val isConfigured = provider in configuredProviders
            ApiKeyStatus(
                provider = provider,
                isConfigured = isConfigured,
                maskedHint = if (isConfigured) "••••••••" else null
            )
        }

        return ProfileApiKeysStatus(statuses)
    }

    /**
     * Decrypts the raw key strictly for executing AI calls.
     * Never call this from presentation or UI layer.
     */
    fun getRawKey(profileId: String, provider: Provider): String? {
        val sql = "SELECT ciphertext, iv FROM api_keys_secure WHERE profile_id = ? AND provider = ? LIMIT 1"
        return db.querySingle(sql, listOf(profileId, provider.name)) { cursor ->
            val ciphertext = cursor.getString(0) ?: return@querySingle null
            val iv = cursor.getString(1) ?: return@querySingle null
            runCatching {
                CryptoUtils.decryptAesGcm(ciphertext, iv, masterKey)
            }.getOrNull()
        }
    }
}
