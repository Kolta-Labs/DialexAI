package com.dialex.data.repository

import com.dialex.data.datasource.ProfileLocalDataSource
import com.dialex.domain.model.ConnectionProfile
import com.dialex.domain.model.ProfileLockConfig
import com.dialex.domain.repository.ProfileRepository
import com.dialex.util.CryptoUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ProfileRepositoryImpl(
    private val dataSource: ProfileLocalDataSource
) : ProfileRepository {
    private val unlockedProfileIds = mutableSetOf<String>()

    override fun getProfiles(): Flow<List<ConnectionProfile>> =
        dataSource.observeProfiles()

    override fun observeActiveProfile(): Flow<ConnectionProfile?> =
        dataSource.observeProfiles().map { profiles ->
            val activeId = dataSource.getActiveProfileId() ?: ConnectionProfile.LOCAL_PROFILE_ID
            profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
        }

    override suspend fun getActiveProfile(): ConnectionProfile? {
        val activeId = dataSource.getActiveProfileId() ?: ConnectionProfile.LOCAL_PROFILE_ID
        return dataSource.getProfile(activeId) ?: dataSource.getAllProfiles().firstOrNull()
    }

    override suspend fun getActiveProfileId(): String? =
        dataSource.getActiveProfileId()

    override suspend fun setActiveProfile(profileId: String) {
        dataSource.setActiveProfileId(profileId)
    }

    override suspend fun getProfile(profileId: String): ConnectionProfile? =
        dataSource.getProfile(profileId)

    override suspend fun saveProfile(profile: ConnectionProfile): ConnectionProfile {
        dataSource.saveProfile(profile)
        return profile
    }

    override suspend fun deleteProfile(profileId: String) {
        dataSource.deleteProfile(profileId)
        unlockedProfileIds.remove(profileId)
    }

    override suspend fun ensureDefaultProfiles() {
        dataSource.initDefaultProfilesIfEmpty()
    }

    override fun verifyPin(profileId: String, pin: String): Boolean {
        val profile = dataSource.getProfile(profileId) ?: return false
        val customPin = profile.lockConfig as? ProfileLockConfig.CustomPin ?: return true
        val computedHash = CryptoUtils.hashPin(pin, customPin.salt)
        val valid = computedHash == customPin.pinHash
        if (valid) {
            unlockedProfileIds.add(profileId)
        }
        return valid
    }

    override suspend fun setProfileLock(profileId: String, lockConfig: ProfileLockConfig) {
        val profile = dataSource.getProfile(profileId) ?: return
        val updated = profile.copy(
            lockConfig = lockConfig,
            updatedAt = System.currentTimeMillis()
        )
        dataSource.saveProfile(updated)
    }

    override fun isProfileUnlocked(profileId: String): Boolean {
        val profile = dataSource.getProfile(profileId) ?: return true
        if (profile.lockConfig is ProfileLockConfig.None) return true
        return profileId in unlockedProfileIds
    }

    override fun setProfileUnlocked(profileId: String, unlocked: Boolean) {
        if (unlocked) {
            unlockedProfileIds.add(profileId)
        } else {
            unlockedProfileIds.remove(profileId)
        }
    }

    override fun isProfileLocked(profileId: String): Boolean {
        val profile = dataSource.getProfile(profileId) ?: return false
        if (profile.lockConfig is ProfileLockConfig.None) return false
        return !isProfileUnlocked(profileId)
    }

    override fun unlockProfileWithPin(profileId: String, pin: String): Boolean {
        val ok = verifyPin(profileId, pin)
        if (ok) {
            setProfileUnlocked(profileId, true)
        }
        return ok
    }

    override fun unlockProfileWithBiometric(profileId: String) {
        setProfileUnlocked(profileId, true)
    }
}
