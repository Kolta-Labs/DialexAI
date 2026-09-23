package com.dialex.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class ProfileType {
    LOCAL,
    REMOTE
}

@Serializable
data class ConnectionProfile(
    val id: String,
    val name: String,
    val type: ProfileType,
    val remoteUrl: String? = null,
    val remoteUsername: String? = null,
    val tailscaleUrl: String? = null,
    val isTailscaleEnabled: Boolean = false,
    val lockConfig: ProfileLockConfig = ProfileLockConfig.None,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val syncMetadata: SyncMetadata = SyncMetadata(entityId = id, profileId = id)
) {
    companion object {
        const val LOCAL_PROFILE_ID = "profile_local"
        const val REMOTE_PROFILE_ID = "profile_remote"

        fun createDefaultLocal(createdAt: Long = 0L): ConnectionProfile = ConnectionProfile(
            id = LOCAL_PROFILE_ID,
            name = "Local Engine",
            type = ProfileType.LOCAL,
            lockConfig = ProfileLockConfig.None,
            createdAt = createdAt,
            updatedAt = createdAt,
            syncMetadata = SyncMetadata(entityId = LOCAL_PROFILE_ID, profileId = LOCAL_PROFILE_ID, updatedAt = createdAt)
        )

        fun createDefaultRemote(
            url: String = "http://127.0.0.1:7890",
            username: String = "",
            tailscaleUrl: String? = null,
            isTailscaleEnabled: Boolean = false,
            createdAt: Long = 0L
        ): ConnectionProfile = ConnectionProfile(
            id = REMOTE_PROFILE_ID,
            name = "Remote Engine",
            type = ProfileType.REMOTE,
            remoteUrl = url,
            remoteUsername = username,
            tailscaleUrl = tailscaleUrl,
            isTailscaleEnabled = isTailscaleEnabled,
            lockConfig = ProfileLockConfig.None,
            createdAt = createdAt,
            updatedAt = createdAt,
            syncMetadata = SyncMetadata(entityId = REMOTE_PROFILE_ID, profileId = REMOTE_PROFILE_ID, updatedAt = createdAt)
        )
    }
}
