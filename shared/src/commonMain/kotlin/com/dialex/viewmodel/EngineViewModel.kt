package com.dialex.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dialex.engine.EngineClient
import com.dialex.model.Agent
import com.dialex.model.ApiKeys
import com.dialex.model.AppState
import com.dialex.model.CliCommands
import com.dialex.model.DebateConfig
import com.dialex.model.Discussion
import com.dialex.model.Project
import com.dialex.model.Provider
import com.dialex.model.defaultModel

/**
 * The engine-backed counterpart to `AppViewModel` (the retired local-only view model) — same
 * method names/shapes wherever the concept carries over, but every mutation is a real HTTP
 * call to a Go engine instead of a local file write, and there is no `runnerFor`/`trackJob`
 * (the engine owns runner selection and turn-loop cancellation now, not this process). This
 * is what `AppShell`/`MainActivity` actually talk to.
 *
 * **Known asymmetry with the old local view model**: the engine never round-trips actual API
 * key values back out (by design — see `GET /settings`'s `apiKeysConfigured` booleans instead
 * of the keys themselves). `state.apiKeys` here only reflects whatever was most recently
 * *sent* via [updateApiKeys] in this process's own lifetime, not what's actually configured
 * on the engine — a freshly connected client can't tell a real key from an unset one just
 * from `state.apiKeys` being blank. A real UI needs the `apiKeysConfigured` flags
 * ([EngineClient.SettingsResponse]) for that, not this field.
 *
 * **Error handling**: every method here can throw (network, auth, engine-side errors) —
 * deliberately *not* swallowed here, since some callers need the thrown exception (or the
 * method's return value on success) to make a decision. What guarantees nothing crashes the
 * app is [lastError] plus [com.dialex.viewmodel.launchGuarded]: every call site in
 * `AppShell`/`MainActivity` uses `scope.launchGuarded(vm) { ... }` instead of a bare
 * `scope.launch { ... }`, which catches anything that escapes, records it in [lastError] for
 * the UI to show as a dismissible banner, and — critically — never lets it reach the
 * coroutine scope's own (app-crashing) uncaught-exception path.
 */
class EngineViewModel(private val client: EngineClient) {
    var state: AppState by mutableStateOf(AppState())
        private set

    /** The most recent error any guarded call site recorded via [launchGuarded] — null means
     * nothing's currently flagged. The UI shows this as a dismissible banner; [clearError]
     * dismisses it. */
    var lastError: String? by mutableStateOf(null)
        private set

    fun reportError(message: String) {
        lastError = message
    }

    fun clearError() {
        lastError = null
    }

    private fun mutate(block: (AppState) -> AppState) {
        state = block(state)
    }

    /** Loads everything from the engine — call once after [EngineClient.login] succeeds,
     * and again any time the UI wants a hard refresh (e.g. after reconnecting). */
    suspend fun refresh() {
        val projects = client.listProjects()
        val discussions = client.listDebates()
        val settings = client.getSettings()
        mutate {
            it.copy(
                projects = projects,
                discussions = discussions,
                cliCommands = settings.cliCommands,
                compactionModel = settings.compactionModel,
                tokenBudget = settings.tokenBudget,
            )
        }
    }

    suspend fun addProject(name: String): Project {
        val project = client.createProject(name)
        mutate { it.copy(projects = it.projects + project) }
        return project
    }

    suspend fun addDiscussion(projectId: String, name: String): Discussion {
        val config = DebateConfig(topic = "", primary = Agent(provider = Provider.ANTHROPIC, model = Provider.ANTHROPIC.defaultModel()))
        val discussion = client.createDebate(projectId, name, config)
        mutate { it.copy(discussions = it.discussions + discussion) }
        return discussion
    }

    suspend fun updateDiscussion(discussion: Discussion) {
        val updated = client.updateDebate(discussion)
        mutate { s -> s.copy(discussions = s.discussions.map { if (it.id == updated.id) updated else it }) }
    }

    suspend fun deleteDiscussion(id: String) {
        client.deleteDebate(id)
        mutate { s -> s.copy(discussions = s.discussions.filterNot { it.id == id }) }
    }

    /** Also removes every discussion inside the project — the engine cascades this itself
     * (see the Go `handleDeleteProject`), this just mirrors that into local state. */
    suspend fun deleteProject(id: String) {
        client.deleteProject(id)
        mutate { s -> s.copy(projects = s.projects.filterNot { it.id == id }, discussions = s.discussions.filterNot { it.projectId == id }) }
    }

    suspend fun updateApiKeys(keys: ApiKeys) {
        val current = state
        client.updateSettings(current.copy(apiKeys = keys))
        mutate { it.copy(apiKeys = keys) }
    }

    suspend fun updateCliCommands(commands: CliCommands) {
        val current = state
        client.updateSettings(current.copy(cliCommands = commands))
        mutate { it.copy(cliCommands = commands) }
    }

    suspend fun updateCompactionModel(model: String) {
        val current = state
        client.updateSettings(current.copy(compactionModel = model))
        mutate { it.copy(compactionModel = model) }
    }

    suspend fun updateTokenBudget(budget: Int) {
        val current = state
        client.updateSettings(current.copy(tokenBudget = budget))
        mutate { it.copy(tokenBudget = budget) }
    }

    /** Halts the debate after the turn in flight — Resume continues from that exact point.
     * Unlike `AppViewModel.pause` this is a real HTTP call (the engine, not this process,
     * owns the turn loop), so it's suspend — the caller launches it in its own scope, same
     * as [start]/[resume] already require. */
    suspend fun pause(discussionId: String) {
        client.pauseDebate(discussionId)
    }

    /** Kills the in-flight turn immediately instead of waiting for it to finish like
     * [pause] does. The engine cancels the turn's context, which propagates to a CLI
     * subprocess as an immediate kill or aborts an in-flight HTTP call. */
    suspend fun hardStop(discussionId: String) {
        client.hardStopDebate(discussionId)
    }

    /** Fresh run from an empty transcript, then follows the live SSE stream until the
     * engine closes it (the run finished — paused, errored, or done) and refreshes this
     * discussion's final state. Suspends for the whole run (the caller launches it in a
     * scope it can track/cancel independently if it wants to) — but [state] reflects RUNNING
     * *immediately* once the engine accepts the start, not only once the run finishes: the UI
     * (e.g. the config screen dismissing itself) reacts to that status flip right away rather
     * than waiting for the whole run to complete. */
    suspend fun start(discussionId: String) {
        client.startDebate(discussionId)
        markRunning(discussionId)
        consumeStream(discussionId)
    }

    suspend fun resume(discussionId: String) {
        client.resumeDebate(discussionId)
        markRunning(discussionId)
        consumeStream(discussionId)
    }

    private fun markRunning(discussionId: String) {
        mutate { s ->
            s.copy(discussions = s.discussions.map { if (it.id == discussionId) it.copy(status = com.dialex.model.DiscussionStatus.RUNNING) else it })
        }
    }

    private suspend fun consumeStream(discussionId: String) {
        client.streamDebate(discussionId).collect { updated ->
            mutate { s ->
                s.copy(discussions = s.discussions.map { d ->
                    if (d.id == discussionId) updated else d
                })
            }
        }
        val finalDiscussion = client.getDebate(discussionId)
        mutate { s -> s.copy(discussions = s.discussions.map { if (it.id == discussionId) finalDiscussion else it }) }
    }

    /** Asks the engine to have the primary agent turn the discussion so far into a
     * self-contained prompt another AI could pick up cold. Persisted on the engine side; the
     * local copy is updated to match so the UI reflects it immediately without a refetch. */
    suspend fun generateHandoffPrompt(discussionId: String): String {
        val prompt = client.generateHandoffPrompt(discussionId)
        mutate { s -> s.copy(discussions = s.discussions.map { if (it.id == discussionId) it.copy(handoffPrompt = prompt) else it }) }
        return prompt
    }
}
