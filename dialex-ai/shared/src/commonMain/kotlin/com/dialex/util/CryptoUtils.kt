package com.dialex.util

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedData(
    val ciphertext: String,
    val iv: String
)

object CryptoUtils {
    private const val GCM_TAG_LENGTH = 128
    private const val IV_LENGTH = 12
    private val secureRandom = SecureRandom()

    fun generateSalt(bytes: Int = 16): String {
        val salt = ByteArray(bytes)
        secureRandom.nextBytes(salt)
        return Base64.getEncoder().encodeToString(salt)
    }

    fun generateRandomKey(bytes: Int = 32): ByteArray {
        val key = ByteArray(bytes)
        secureRandom.nextBytes(key)
        return key
    }

    /**
     * Salted SHA-256 hash for PIN verification.
     */
    fun hashPin(pin: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt.toByteArray(Charsets.UTF_8))
        val hash = digest.digest(pin.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(hash)
    }

    /**
     * Encrypts plaintext using AES-256-GCM.
     */
    fun encryptAesGcm(plaintext: String, key: ByteArray): EncryptedData {
        val iv = ByteArray(IV_LENGTH)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val paramSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, paramSpec)

        val encryptedBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return EncryptedData(
            ciphertext = Base64.getEncoder().encodeToString(encryptedBytes),
            iv = Base64.getEncoder().encodeToString(iv)
        )
    }

    /**
     * Decrypts ciphertext using AES-256-GCM.
     */
    fun decryptAesGcm(ciphertext: String, iv: String, key: ByteArray): String {
        val cipherBytes = Base64.getDecoder().decode(ciphertext)
        val ivBytes = Base64.getDecoder().decode(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val paramSpec = GCMParameterSpec(GCM_TAG_LENGTH, ivBytes)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, paramSpec)

        val decryptedBytes = cipher.doFinal(cipherBytes)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    fun decryptAesGcm(data: EncryptedData, key: ByteArray): String =
        decryptAesGcm(data.ciphertext, data.iv, key)
}
