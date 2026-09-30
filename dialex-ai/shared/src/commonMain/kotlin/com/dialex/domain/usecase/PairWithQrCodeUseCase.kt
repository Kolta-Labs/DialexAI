package com.dialex.domain.usecase

import com.dialex.domain.model.ConnectionProfile
import com.dialex.domain.model.ProfileType
import com.dialex.domain.repository.ProfileRepository
import com.dialex.engine.EngineClient

data class QrPairingResult(
    val success: Boolean,
    val serverUrl: String? = null,
    val username: String? = null,
    val token: String? = null,
    val error: String? = null
)

/**
 * UseCase to parse and authenticate pairing payloads scanned via QR code.
 * Payload format: `dialex://pair?url=http://192.168.1.50:7890&user=admin&token=...`
 */
class PairWithQrCodeUseCase(
    private val profileRepository: ProfileRepository,
    private val engineClient: EngineClient
) {
    suspend operator fun invoke(rawPayload: String): QrPairingResult {
        if (!rawPayload.startsWith("dialex://pair?", ignoreCase = true)) {
            return QrPairingResult(
                success = false,
                error = "Invalid QR code format. Expected a Dialex pairing code."
            )
        }

        val query = rawPayload.substringAfter("dialex://pair?")
        val params = query.split("&").associate { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) parts[0] to decodeParam(parts[1]) else parts[0] to ""
        }

        val serverUrl = params["url"]?.ifBlank { null }
            ?: return QrPairingResult(success = false, error = "Missing server URL in pairing code.")
        val username = params["user"]?.ifBlank { "admin" } ?: "admin"
        val token = params["token"]?.ifBlank { null }
            ?: return QrPairingResult(success = false, error = "Missing security token in pairing code.")

        return try {
            // Configure remote connection profile
            val remoteProfile = ConnectionProfile.createDefaultRemote(
                url = serverUrl,
                username = username,
                createdAt = System.currentTimeMillis()
            )
            profileRepository.saveProfile(remoteProfile)
            profileRepository.setActiveProfile(remoteProfile.id)

            // Authenticate with engine client using token
            engineClient.setBaseUrl(serverUrl)
            engineClient.setAuthToken(token, username)

            QrPairingResult(
                success = true,
                serverUrl = serverUrl,
                username = username,
                token = token
            )
        } catch (e: Exception) {
            QrPairingResult(
                success = false,
                error = "Failed to connect to server: ${e.message}"
            )
        }
    }

    private fun decodeParam(value: String): String {
        return value.replace("%3A", ":", ignoreCase = true)
            .replace("%2F", "/", ignoreCase = true)
            .replace("%3F", "?", ignoreCase = true)
            .replace("%3D", "=", ignoreCase = true)
            .replace("%26", "&", ignoreCase = true)
            .replace("%25", "%", ignoreCase = true)
    }
}
