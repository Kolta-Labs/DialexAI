package com.dialex.data.datasource

import com.dialex.data.db.SqlCursor
import com.dialex.data.db.SqlDatabase
import com.dialex.domain.model.ConnectionProfile
import com.dialex.domain.model.LockMode
import com.dialex.domain.model.ProfileLockConfig
import com.dialex.domain.model.ProfileType
import com.dialex.domain.model.SyncMetadata
import com.dialex.domain.model.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ProfileLocalDataSource(private val db: SqlDatabase) {
    private val profilesFlow = MutableStateFlow<List<ConnectionProfile>>(emptyList())

    init {
        refreshFlow()
    }

    private fun refreshFlow() {
        profilesFlow.value = getAllProfiles()
    }

    fun observeProfiles(): Flow<List<ConnectionProfile>> = profilesFlow.asStateFlow()

    fun getAllProfiles(): List<ConnectionProfile> {
        val sql = """
            SELECT id, name, type, remote_url, remote_username, lock_mode, lock_data,
                   created_at, updated_at, version, is_deleted, sync_status
            FROM profiles
            WHERE is_deleted = 0
            ORDER BY created_at ASC
        """.trimIndent()

        return db.query(sql) { cursor -> mapProfile(cursor) }
    }

    fun getProfile(id: String): ConnectionProfile? {
        val sql = """
            SELECT id, name, type, remote_url, remote_username, lock_mode, lock_data,
                   created_at, updated_at, version, is_deleted, sync_status
            FROM profiles
            WHERE id = ? AND is_deleted = 0
            LIMIT 1
        """.trimIndent()

        return db.querySingle(sql, listOf(id)) { cursor -> mapProfile(cursor) }
    }

    fun saveProfile(profile: ConnectionProfile) {
        val lockMode = when (profile.lockConfig) {
            is ProfileLockConfig.None -> LockMode.NONE.name
            is ProfileLockConfig.CustomPin -> LockMode.PIN.name
            is ProfileLockConfig.DeviceBiometricOrPin -> LockMode.BIOMETRIC.name
        }

        val lockData = when (val cfg = profile.lockConfig) {
            is ProfileLockConfig.CustomPin -> "${cfg.pinHash}:${cfg.salt}"
            else -> null
        }

        val sql = """
            INSERT OR REPLACE INTO profiles (
                id, name, type, remote_url, remote_username, lock_mode, lock_data,
                created_at, updated_at, version, is_deleted, sync_status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        db.exec(
            sql,
            listOf(
                profile.id,
                profile.name,
                profile.type.name,
                profile.remoteUrl,
                profile.remoteUsername,
                lockMode,
                lockData,
                profile.createdAt,
                profile.updatedAt,
                profile.syncMetadata.version,
                if (profile.syncMetadata.isDeleted) 1 else 0,
                profile.syncMetadata.syncStatus.name
            )
        )
        refreshFlow()
    }

    fun deleteProfile(id: String) {
        // Soft delete for sync tombstone
        val now = System.currentTimeMillis()
        val sql = "UPDATE profiles SET is_deleted = 1, updated_at = ?, sync_status = 'DIRTY_LOCAL' WHERE id = ?"
        db.exec(sql, listOf(now, id))
        refreshFlow()
    }

    fun getActiveProfileId(): String? {
        val sql = "SELECT active_profile_id FROM active_profile_pointer WHERE key = 'current_profile' LIMIT 1"
        return db.querySingle(sql) { cursor -> cursor.getString(0) }
    }

    fun setActiveProfileId(profileId: String) {
        val sql = "INSERT OR REPLACE INTO active_profile_pointer (key, active_profile_id) VALUES ('current_profile', ?)"
        db.exec(sql, listOf(profileId))
    }

    fun initDefaultProfilesIfEmpty() {
        val existing = getAllProfiles()
        if (existing.isEmpty()) {
            val now = System.currentTimeMillis()
            saveProfile(ConnectionProfile.createDefaultLocal(now))
            saveProfile(ConnectionProfile.createDefaultRemote(createdAt = now))
            setActiveProfileId(ConnectionProfile.LOCAL_PROFILE_ID)
        }
    }

    private fun mapProfile(cursor: SqlCursor): ConnectionProfile {
        val id = cursor.getString(0) ?: ""
        val name = cursor.getString(1) ?: ""
        val typeStr = cursor.getString(2) ?: ProfileType.LOCAL.name
        val type = runCatching { ProfileType.valueOf(typeStr) }.getOrDefault(ProfileType.LOCAL)
        val remoteUrl = cursor.getString(3)
        val remoteUsername = cursor.getString(4)
        val lockModeStr = cursor.getString(5) ?: LockMode.NONE.name
        val lockData = cursor.getString(6)
        val createdAt = cursor.getLong(7)
        val updatedAt = cursor.getLong(8)
        val version = cursor.getLong(9)
        val isDeleted = cursor.getInt(10) == 1
        val syncStatusStr = cursor.getString(11) ?: SyncStatus.SYNCED.name
        val syncStatus = runCatching { SyncStatus.valueOf(syncStatusStr) }.getOrDefault(SyncStatus.SYNCED)

        val lockConfig = when (runCatching { LockMode.valueOf(lockModeStr) }.getOrDefault(LockMode.NONE)) {
            LockMode.NONE -> ProfileLockConfig.None
            LockMode.PIN -> {
                if (lockData != null && ":" in lockData) {
                    val parts = lockData.split(":", limit = 2)
                    ProfileLockConfig.CustomPin(pinHash = parts[0], salt = parts[1])
                } else {
                    ProfileLockConfig.None
                }
            }
            LockMode.BIOMETRIC -> ProfileLockConfig.DeviceBiometricOrPin
        }

        return ConnectionProfile(
            id = id,
            name = name,
            type = type,
            remoteUrl = remoteUrl,
            remoteUsername = remoteUsername,
            lockConfig = lockConfig,
            createdAt = createdAt,
            updatedAt = updatedAt,
            syncMetadata = SyncMetadata(
                entityId = id,
                profileId = id,
                version = version,
                updatedAt = updatedAt,
                isDeleted = isDeleted,
                syncStatus = syncStatus
            )
        )
    }
}
