package com.dialex.runner

import com.dialex.model.Agent
import com.dialex.model.DebateMessage

/** One turn's reply. Token counts are best-effort — API providers report them in the
 * response, CLI subprocesses generally don't expose them at all, so both are null there. */
data class AgentReply(
    val content: String,
    val tokensIn: Int? = null,
    val tokensOut: Int? = null,
    val tokensCached: Int? = null,
)

/** Produces one turn's reply for an agent given the debate so far. */
interface AgentRunner {
    suspend fun respond(
        agent: Agent,
        topic: String,
        commonContext: String,
        commonInstructions: String,
        transcript: List<DebateMessage>,
        // Swaps just the model for this one call (e.g. compaction summaries use a cheap
        // model regardless of what the user picked for the agent). Null = use agent.model /
        // the configured CLI command as-is.
        modelOverride: String? = null,
    ): AgentReply
}
