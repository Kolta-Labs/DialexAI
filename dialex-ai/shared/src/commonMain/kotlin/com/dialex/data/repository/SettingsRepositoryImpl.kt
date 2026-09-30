package com.dialex.data.repository

import com.dialex.data.datasource.EngineDataSource
import com.dialex.domain.repository.SettingsRepository
import com.dialex.model.ApiKeys
import com.dialex.model.AppState
import com.dialex.model.CliCommands
import com.dialex.model.CompactionSettings

class SettingsRepositoryImpl(private val dataSource: EngineDataSource) : SettingsRepository {
    override suspend fun getSettings(): AppState = dataSource.getSettings()

    override suspend fun updateApiKeys(keys: ApiKeys) {
        val current = dataSource.getSettings()
        dataSource.updateSettings(current.copy(apiKeys = keys))
    }

    override suspend fun updateCliCommands(commands: CliCommands) {
        val current = dataSource.getSettings()
        dataSource.updateSettings(current.copy(cliCommands = commands))
    }

    override suspend fun updateCompactionModel(model: String) {
        val current = dataSource.getSettings()
        dataSource.updateSettings(current.copy(compactionModel = model))
    }

    override suspend fun updateCompactionSettings(settings: CompactionSettings) {
        val current = dataSource.getSettings()
        dataSource.updateSettings(current.copy(compactionSettings = settings))
    }

    override suspend fun updateTokenBudget(budget: Int) {
        val current = dataSource.getSettings()
        dataSource.updateSettings(current.copy(tokenBudget = budget))
    }

    override suspend fun updateMasterInstructions(instructions: String) {
        val current = dataSource.getSettings()
        dataSource.updateSettings(current.copy(masterInstructions = instructions))
    }

    override suspend fun updateDebatePolicy(policy: com.dialex.model.DebatePolicy) {
        val current = dataSource.getSettings()
        dataSource.updateSettings(current.copy(debatePolicy = policy))
    }

    override suspend fun updateAgentDefaults(defaults: com.dialex.model.ProviderAgentDefaults) {
        val current = dataSource.getSettings()
        dataSource.updateSettings(current.copy(agentDefaults = defaults))
    }

    override suspend fun getAvailableModels(): Map<String, List<String>> =
        dataSource.getAvailableModels()

    override suspend fun getCliStatus(): Map<String, Boolean> =
        dataSource.getCliStatus()

    override suspend fun getCliLogins(): Map<String, Boolean> =
        dataSource.getCliLogins()
}
