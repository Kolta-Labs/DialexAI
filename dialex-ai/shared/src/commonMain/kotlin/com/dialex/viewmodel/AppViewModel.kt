package com.dialex.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dialex.model.Agent
import com.dialex.model.ApiKeys
import com.dialex.model.AppState
import com.dialex.model.CliCommands
import com.dialex.model.DebateConfig
import com.dialex.model.DebateMessage
import com.dialex.model.Discussion
import com.dialex.model.DiscussionStatus
import com.dialex.model.Project
import com.dialex.model.Provider
import com.dialex.model.defaultModel
import com.dialex.orchestrator.DebateOrchestrator
import com.dialex.runner.AgentRunner
import com.dialex.store.AppStore
import kotlin.random.Random

private fun newId() = Random.nextLong().toString(36)

/** Holds all app state, persists on every mutation. UI reads `state`, calls the methods below. */
class AppViewModel(private val store: AppStore) {
    // RUNNING is only meaningful while this process actually holds a Job for it (tracked in
    // `runningJobs` below) — that map always starts empty on a fresh process, so a RUNNING
    // status loaded from disk is necessarily stale (the app closed, crashed, or was killed
    // mid-debate last time). Left as-is it shows a permanent "thinking" bubble with nothing
    // behind it, and hard-stop/pause have no Job to act on. Flip it to PAUSED once at load —
    // everything needed to resume is already in the transcript.
    var state: AppState by mutableStateOf(
        store.load().let { loaded ->
            loaded.copy(discussions = loaded.discussions.map {
                if (it.status == DiscussionStatus.RUNNING) it.copy(status = DiscussionStatus.PAUSED) else it
            })
        },
    )
        private set

    private fun mutate(block: (AppState) -> AppState) {
        state = block(state)
        store.save(state)
    }

    fun addProject(name: String): Project {
        val project = Project(newId(), name)
        mutate { it.copy(projects = it.projects + project) }
        return project
    }

    fun addDiscussion(projectId: String, name: String): Discussion {
        val discussion = Discussion(
            id = newId(),
            projectId = projectId,
            name = name,
            // Primary defaults to Claude — configurable per-discussion in setup.
            config = DebateConfig(topic = "", primary = Agent(provider = Provider.ANTHROPIC, model = Provider.ANTHROPIC.defaultModel())),
        )
        mutate { it.copy(discussions = it.discussions + discussion) }
        return discussion
    }

    fun updateDiscussion(discussion: Discussion) {
        mutate { s -> s.copy(discussions = s.discussions.map { if (it.id == discussion.id) discussion else it }) }
    }

    fun deleteDiscussion(id: String) {
        mutate { s -> s.copy(discussions = s.discussions.filterNot { it.id == id }) }
    }

    /** Also removes every discussion inside the project — nothing is left orphaned. */
    fun deleteProject(id: String) {
        mutate { s -> s.copy(projects = s.projects.filterNot { it.id == id }, discussions = s.discussions.filterNot { it.projectId == id }) }
    }

    fun updateApiKeys(keys: ApiKeys) {
        mutate { it.copy(apiKeys = keys) }
    }

    fun updateCliCommands(commands: CliCommands) {
        mutate { it.copy(cliCommands = commands) }
    }

    fun updateCompactionModel(model: String) {
        mutate { it.copy(compactionModel = model) }
    }

    fun updateTokenBudget(budget: Int) {
        mutate { it.copy(tokenBudget = budget) }
    }

    // One flag per running discussion; the orchestrator polls it before every turn.
    private val pauseFlags = mutableMapOf<String, Boolean>()

    /** Halts the debate after the turn in flight — Resume continues from that exact point. */
    fun pause(discussionId: String) {
        pauseFlags[discussionId] = true
    }

    // The Job for whichever discussion is currently running, so hardStop can cancel it.
    private val runningJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    /** Called by the platform right after launching [start]/[resume] in its own coroutine,
     * so [hardStop] has a Job to cancel. */
    fun trackJob(discussionId: String, job: kotlinx.coroutines.Job) {
        runningJobs[discussionId] = job
    }

    /** Kills the in-flight turn immediately (process destroyed / HTTP call aborted) instead
     * of waiting for it to finish like [pause] does. Nothing from that turn is kept, so
     * Resume just re-asks it — same "restart from current point" as a normal pause. */
    fun hardStop(discussionId: String) {
        runningJobs[discussionId]?.cancel()
    }

    /** Fresh run from an empty transcript. Used for the first start, and for "Restart with
     * these changes" from the config screen — always discards whatever was there before. */
    suspend fun start(discussionId: String, runnerFor: (Agent) -> AgentRunner) {
        updateDiscussion(state.discussions.first { it.id == discussionId }.copy(transcript = emptyList(), conclusion = null))
        runOrchestrator(discussionId, runnerFor)
    }

    /** Continues a PAUSED or ERROR discussion from its last successful turn. For ERROR,
     * the transcript's last entry is the error message itself — that gets dropped first
     * so it isn't fed back to the agents as context and doesn't skew the resume point. */
    suspend fun resume(discussionId: String, runnerFor: (Agent) -> AgentRunner) {
        val discussion = state.discussions.first { it.id == discussionId }
        val cleaned = discussion.transcript.dropLastWhile { it.isError }
        if (cleaned.size != discussion.transcript.size) updateDiscussion(discussion.copy(transcript = cleaned))
        runOrchestrator(discussionId, runnerFor)
    }

    /** Asks the primary agent to turn the discussion so far into a self-contained prompt
     * another AI (with zero prior context) could pick up cold. A one-off side call —
     * doesn't touch the discussion's own transcript/status, so it's safe regardless of
     * Paused/Done/Error. */
    suspend fun generateHandoffPrompt(discussionId: String, runnerFor: (Agent) -> AgentRunner): String {
        val discussion = state.discussions.first { it.id == discussionId }
        val primary = discussion.config.primary
        val instruction = "Summarize this debate into a comprehensive, ready-to-paste prompt for a " +
            "DIFFERENT AI assistant that has no prior context at all. Include: the topic, essential " +
            "background/context, each participant's key positions and strongest arguments, how the " +
            "discussion evolved, and the conclusion (or, if unresolved, the open questions). Write it " +
            "as the prompt itself — something to hand directly to another AI to bring it fully up to " +
            "speed — not commentary about the summary."
        val reply = runnerFor(primary).respond(
            agent = primary.copy(systemPrompt = instruction),
            topic = discussion.config.topic,
            commonContext = discussion.config.commonContext,
            commonInstructions = "",
            transcript = discussion.transcript,
        )
        return reply.content
    }

    private suspend fun runOrchestrator(discussionId: String, runnerFor: (Agent) -> AgentRunner) {
        val discussion = state.discussions.first { it.id == discussionId }
        pauseFlags[discussionId] = false
        updateDiscussion(discussion.copy(status = DiscussionStatus.RUNNING))

        val orchestrator = DebateOrchestrator(runnerFor)
        val result = try {
            orchestrator.run(
                config = discussion.config,
                initialTranscript = discussion.transcript,
                isStopped = { pauseFlags[discussionId] == true },
                compactionModel = state.compactionModel,
                tokenBudget = state.tokenBudget,
            ) { msg ->
                val current = state.discussions.first { it.id == discussionId }
                updateDiscussion(current.copy(transcript = current.transcript + msg))
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            // Hard stop. Regular (non-suspending) cleanup is fine here — the rethrow below
            // is what actually lets the coroutine finish cancelling.
            runningJobs.remove(discussionId)
            pauseFlags.remove(discussionId)
            val current = state.discussions.first { it.id == discussionId }
            updateDiscussion(current.copy(status = DiscussionStatus.PAUSED))
            throw c
        } catch (t: Throwable) {
            // Safety net — the orchestrator already catches per-turn failures itself, this
            // only fires if something outside a turn (e.g. building the runner) throws.
            val current = state.discussions.first { it.id == discussionId }
            val message = t.message ?: t.toString()
            val errorMsg = DebateMessage(discussion.config.primary.provider, 0, message, isError = true)
            updateDiscussion(current.copy(transcript = current.transcript + errorMsg))
            null
        }
        pauseFlags.remove(discussionId)
        runningJobs.remove(discussionId)
        val finished = state.discussions.first { it.id == discussionId }
        val status = when {
            result == null || result.error != null -> DiscussionStatus.FAILED
            result.warning != null -> DiscussionStatus.COMPLETED_WITH_WARNING
            result.paused -> DiscussionStatus.PAUSED
            else -> DiscussionStatus.COMPLETED
        }
        updateDiscussion(
            finished.copy(
                status = status,
                conclusion = result?.conclusion,
                warning = result?.warning,
                isConsensusReached = result?.isConsensusReached ?: false,
                earlyExitReason = result?.earlyExitReason
            )
        )
    }
}
