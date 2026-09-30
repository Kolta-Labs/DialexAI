package com.dialex.domain.repository

import com.dialex.model.ApiKeys
import com.dialex.model.AppState
import com.dialex.model.CliCommands
import com.dialex.model.CompactionSettings

/** Domain-layer contract for global settings. Implemented in data layer; used by UseCases. */
interface SettingsRepository {
    suspend fun getSettings(): AppState
    suspend fun updateApiKeys(keys: ApiKeys)
    suspend fun updateCliCommands(commands: CliCommands)
    suspend fun updateCompactionModel(model: String)
    suspend fun updateCompactionSettings(settings: CompactionSettings)
    suspend fun updateTokenBudget(budget: Int)
    suspend fun updateMasterInstructions(instructions: String)
    suspend fun updateDebatePolicy(policy: com.dialex.model.DebatePolicy)
    suspend fun updateAgentDefaults(defaults: com.dialex.model.ProviderAgentDefaults)
    /** Fetches the list of models available from each provider from the engine. */
    suspend fun getAvailableModels(): Map<String, List<String>>
    /** Fetches the status of CLI tools on the system running the engine. */
    suspend fun getCliStatus(): Map<String, Boolean>
    /** Fetches the login status of CLI tools on the system running the engine. */
    suspend fun getCliLogins(): Map<String, Boolean>
}
