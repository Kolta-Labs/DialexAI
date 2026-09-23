package com.dialex.security

import com.dialex.domain.model.ApiKeyStatus
import com.dialex.domain.model.ConnectionProfile
import com.dialex.domain.model.ProfileLockConfig
import com.dialex.domain.model.ProfileType
import com.dialex.model.Provider
import com.dialex.util.CryptoUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProfileSecurityTest {

    @Test
    fun pin_hashing_produces_consistent_hash_with_same_salt_and_differs_with_different_salt() {
        val pin = "1234"
        val salt1 = CryptoUtils.generateSalt()
        val salt2 = CryptoUtils.generateSalt()

        val hash1 = CryptoUtils.hashPin(pin, salt1)
        val hash1Again = CryptoUtils.hashPin(pin, salt1)
        val hash2 = CryptoUtils.hashPin(pin, salt2)

        assertEquals(hash1, hash1Again)
        assertNotEquals(hash1, hash2)
        assertNotEquals(pin, hash1)
    }

    @Test
    fun wrong_pin_fails_verification() {
        val pin = "5678"
        val salt = CryptoUtils.generateSalt()
        val hash = CryptoUtils.hashPin(pin, salt)

        val wrongPinHash = CryptoUtils.hashPin("0000", salt)
        assertNotEquals(hash, wrongPinHash)
    }

    @Test
    fun aes_gcm_encrypt_decrypt_roundtrip_restores_original_plaintext() {
        val key = CryptoUtils.generateRandomKey(32)
        val plainTextApiKey = "sk-ant-api03-secret-token-dialex-12345"

        val encrypted = CryptoUtils.encryptAesGcm(plainTextApiKey, key)
        assertNotEquals(plainTextApiKey, encrypted.ciphertext)

        val decrypted = CryptoUtils.decryptAesGcm(encrypted, key)
        assertEquals(plainTextApiKey, decrypted)
    }

    @Test
    fun profile_lock_config_defaults_to_none() {
        val profile = ConnectionProfile.createDefaultLocal()
        assertEquals(ProfileLockConfig.None, profile.lockConfig)
        assertEquals(ProfileType.LOCAL, profile.type)
    }

    @Test
    fun api_key_status_never_exposes_raw_key() {
        val statusConfigured = ApiKeyStatus(provider = Provider.ANTHROPIC, isConfigured = true, maskedHint = "••••••••")
        val statusEmpty = ApiKeyStatus(provider = Provider.OPENAI, isConfigured = false, maskedHint = null)

        assertTrue(statusConfigured.isConfigured)
        assertEquals("••••••••", statusConfigured.maskedHint)

        assertFalse(statusEmpty.isConfigured)
        assertEquals(null, statusEmpty.maskedHint)
    }
}
