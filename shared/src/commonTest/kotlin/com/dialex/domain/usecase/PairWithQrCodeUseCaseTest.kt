package com.dialex.domain.usecase

import com.dialex.domain.model.ConnectionProfile
import com.dialex.domain.model.ProfileLockConfig
import com.dialex.domain.model.ProfileType
import com.dialex.domain.repository.ProfileRepository
import com.dialex.engine.EngineClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FakeProfileRepository : ProfileRepository {
    private val profiles = mutableListOf<ConnectionProfile>()
    private val profilesFlow = MutableStateFlow<List<ConnectionProfile>>(emptyList())
    private val activeProfileState = MutableStateFlow<ConnectionProfile?>(null)

    override fun getProfiles(): Flow<List<ConnectionProfile>> = profilesFlow.asStateFlow()
    override fun observeActiveProfile(): Flow<ConnectionProfile?> = activeProfileState.asStateFlow()
    override suspend fun getActiveProfile(): ConnectionProfile? = activeProfileState.value
    override suspend fun getActiveProfileId(): String? = activeProfileState.value?.id
    override suspend fun getProfile(profileId: String): ConnectionProfile? = profiles.firstOrNull { it.id == profileId }
    override suspend fun setActiveProfile(profileId: String) {
        activeProfileState.value = profiles.firstOrNull { it.id == profileId }
    }
    override suspend fun saveProfile(profile: ConnectionProfile): ConnectionProfile {
        profiles.removeAll { it.id == profile.id }
        profiles.add(profile)
        profilesFlow.value = profiles.toList()
        return profile
    }
    override suspend fun deleteProfile(profileId: String) {
        profiles.removeAll { it.id == profileId }
        profilesFlow.value = profiles.toList()
    }
    override suspend fun ensureDefaultProfiles() {}
    override fun verifyPin(profileId: String, pin: String): Boolean = true
    override suspend fun setProfileLock(profileId: String, lockConfig: ProfileLockConfig) {}
    override fun isProfileUnlocked(profileId: String): Boolean = true
    override fun setProfileUnlocked(profileId: String, unlocked: Boolean) {}
    override fun isProfileLocked(profileId: String): Boolean = false
    override fun unlockProfileWithPin(profileId: String, pin: String): Boolean = true
    override fun unlockProfileWithBiometric(profileId: String) {}
}

class PairWithQrCodeUseCaseTest {

    @Test
    fun valid_qr_payload_configures_profile_and_auth_token() = runBlocking {
        val repo = FakeProfileRepository()
        val client = EngineClient("http://localhost:7890")
        val useCase = PairWithQrCodeUseCase(repo, client)

        val qrCode = "dialex://pair?url=http%3A%2F%2F192.168.1.50%3A7890&user=alice&token=eyJhbGciOiJIUzI1NiJ9.sample"
        val result = useCase(qrCode)

        assertTrue(result.success)
        assertEquals("http://192.168.1.50:7890", result.serverUrl)
        assertEquals("alice", result.username)
        assertEquals("eyJhbGciOiJIUzI1NiJ9.sample", result.token)

        assertEquals("eyJhbGciOiJIUzI1NiJ9.sample", client.token)
        assertEquals("alice", client.currentUsername)

        val active = repo.getActiveProfile()
        assertEquals(ProfileType.REMOTE, active?.type)
        assertEquals("http://192.168.1.50:7890", active?.remoteUrl)
    }

    @Test
    fun invalid_scheme_returns_error() = runBlocking {
        val repo = FakeProfileRepository()
        val client = EngineClient("http://localhost:7890")
        val useCase = PairWithQrCodeUseCase(repo, client)

        val result = useCase("https://example.com/not-a-dialex-qr")
        assertFalse(result.success)
        assertTrue(result.error?.contains("Invalid QR code") == true)
    }

    @Test
    fun missing_token_returns_error() = runBlocking {
        val repo = FakeProfileRepository()
        val client = EngineClient("http://localhost:7890")
        val useCase = PairWithQrCodeUseCase(repo, client)

        val result = useCase("dialex://pair?url=http://192.168.1.50:7890&user=admin")
        assertFalse(result.success)
        assertTrue(result.error?.contains("Missing security token") == true)
    }
}
