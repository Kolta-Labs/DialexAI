package com.dialex.domain.repository

import com.dialex.domain.model.ConnectionProfile
import com.dialex.domain.model.ProfileLockConfig
import kotlinx.coroutines.flow.Flow

interface ProfileRepository {
    fun getProfiles(): Flow<List<ConnectionProfile>>
    fun observeActiveProfile(): Flow<ConnectionProfile?>
    suspend fun getActiveProfile(): ConnectionProfile?
    suspend fun getActiveProfileId(): String?
    suspend fun setActiveProfile(profileId: String)
    suspend fun getProfile(profileId: String): ConnectionProfile?
    suspend fun saveProfile(profile: ConnectionProfile): ConnectionProfile
    suspend fun deleteProfile(profileId: String)
    suspend fun ensureDefaultProfiles()

    /** PIN & security checks */
    fun verifyPin(profileId: String, pin: String): Boolean
    suspend fun setProfileLock(profileId: String, lockConfig: ProfileLockConfig)

    /** In-memory session unlock state for the current app run */
    fun isProfileUnlocked(profileId: String): Boolean
    fun setProfileUnlocked(profileId: String, unlocked: Boolean)
    fun isProfileLocked(profileId: String): Boolean
    fun unlockProfileWithPin(profileId: String, pin: String): Boolean
    fun unlockProfileWithBiometric(profileId: String)
}
