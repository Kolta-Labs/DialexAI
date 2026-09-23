package com.dialex.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class SyncStatus {
    SYNCED,
    DIRTY_LOCAL,
    PENDING_SYNC,
    CONFLICT
}

/**
 * Foundation for cross-device and client-server synchronization.
 * Attached to every syncable entity (Projects, Discussions, Messages, Settings).
 */
@Serializable
data class SyncMetadata(
    val entityId: String = "",
    val profileId: String = "",
    val version: Long = 1L,
    val updatedAt: Long = 0L,
    val isDeleted: Boolean = false,
    val syncStatus: SyncStatus = SyncStatus.SYNCED
)
