package com.dialex.data.datasource

import com.dialex.domain.model.DomainException
import com.dialex.domain.model.ElevateResult
import com.dialex.domain.model.SocraticDigest
import com.dialex.domain.model.SocraticElevateRequest
import com.dialex.domain.model.SocraticTurnRequest
import com.dialex.domain.model.SocraticTurnResponse
import com.dialex.engine.EngineClient
import com.dialex.engine.EngineException
import com.dialex.model.ApiKeys
import com.dialex.model.AppState
import com.dialex.model.CliCommands
import com.dialex.model.CompactionSettings
import com.dialex.model.DebateConfig
import com.dialex.model.Discussion
import com.dialex.model.PredefinedPersona
import com.dialex.model.Project
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow

/**
 * The only class in the Kotlin codebase allowed to directly call [EngineClient].
 *
 * Every network/IO exception from Ktor is caught here and rethrown as a typed
 * [DomainException] — nothing above this boundary ever sees a raw [ClientRequestException].
 *
 * Call chain:  ViewModel → UseCase → Repository (interface) → **RepositoryImpl → EngineDataSource** → EngineClient
 */
class EngineDataSource(private val client: EngineClient) {

    // ── Projects ──────────────────────────────────────────────────────────────

    suspend fun listProjects(): List<Project> = wrap { client.listProjects() }
    suspend fun createProject(name: String): Project = wrap { client.createProject(name) }
    suspend fun updateProject(project: Project): Project = wrap { client.updateProject(project) }
    suspend fun deleteProject(id: String) = wrap { client.deleteProject(id) }

    // ── Discussions ───────────────────────────────────────────────────────────

    suspend fun listDiscussions(projectId: String? = null): List<Discussion> =
        wrap { client.listDebates(projectId) }

    suspend fun getDiscussion(id: String): Discussion = wrap { client.getDebate(id) }

    suspend fun createDiscussion(
        projectId: String,
        name: String,
        config: DebateConfig,
        mode: com.dialex.domain.model.DiscussionMode = com.dialex.domain.model.DiscussionMode.COUNCIL,
        socraticConfig: com.dialex.domain.model.SocraticConfig? = null,
    ): Discussion =
        wrap { client.createDebate(projectId, name, config, mode, socraticConfig) }

    suspend fun updateDiscussion(discussion: Discussion): Discussion =
        wrap { client.updateDebate(discussion) }

    suspend fun deleteDiscussion(id: String) = wrap { client.deleteDebate(id) }

    suspend fun duplicateDiscussion(id: String): Discussion =
        wrap { client.duplicateDebate(id) }

    suspend fun startDiscussion(id: String) = wrap { client.startDebate(id) }
    suspend fun pauseDiscussion(id: String) = wrap { client.pauseDebate(id) }
    suspend fun stopDiscussion(id: String) = wrap { client.hardStopDebate(id) }
    suspend fun resumeDiscussion(id: String) = wrap { client.resumeDebate(id) }

    fun streamDiscussion(id: String): Flow<Discussion> = client.streamDebate(id)

    suspend fun generateHandoffPrompt(id: String): String =
        wrap { client.generateHandoffPrompt(id) }

    suspend fun generateDeliverableFormat(discussionId: String, format: com.dialex.model.DeliverableFormat): String =
        wrap { client.generateDeliverable(discussionId, format.name) }

    suspend fun generateDiscussionTitle(id: String): String =
        wrap { client.generateDebateTitle(id) }

    suspend fun setupDiscussionWithAi(request: com.dialex.domain.model.AiSetupRequest): Discussion =
        wrap { client.setupDiscussionWithAi(request) }

    suspend fun getDiscussionUsage(id: String): EngineClient.UsageResponse =
        wrap { client.getDiscussionUsage(id) }

    // ── Bayesian Credence & Epistemic Uncertainty ────────────────────────────
    suspend fun getCredenceLedger(id: String): com.dialex.domain.model.CredenceLedger =
        wrap { client.getCredenceLedger(id) }

    suspend fun recalculateCredence(id: String): com.dialex.domain.model.CredenceLedger =
        wrap { client.recalculateCredence(id) }

    // ── Personas ──────────────────────────────────────────────────────────────

    suspend fun listPersonas(): List<PredefinedPersona> = wrap { client.listPersonas() }
    suspend fun createPersona(persona: PredefinedPersona): PredefinedPersona =
        wrap { client.createPersona(persona) }
    suspend fun deletePersona(id: String) = wrap { client.deletePersona(id) }
    suspend fun importPersonas(json: String): Int = wrap { client.importPersonas(json) }
    suspend fun exportPersonas(): String = wrap { client.exportPersonas() }
    suspend fun chatPersona(request: com.dialex.domain.model.PersonaChatRequest): com.dialex.domain.model.PersonaChatResponse =
        wrap { client.chatPersona(request) }

    suspend fun getPersonaDna(id: String): com.dialex.domain.model.PersonaDNA =
        wrap { client.getPersonaDna(id) }
    suspend fun updatePersonaDna(id: String, dna: com.dialex.domain.model.PersonaDNA): com.dialex.domain.model.PersonaDNA =
        wrap { client.updatePersonaDna(id, dna) }
    suspend fun listBuiltinHeuristics(): List<com.dialex.domain.model.HeuristicRule> =
        wrap { client.listBuiltinHeuristics() }
    suspend fun compileDnaPrompt(dna: com.dialex.domain.model.PersonaDNA): String =
        wrap { client.compileDnaPrompt(dna) }
    suspend fun importPersonaDna(content: String, format: String = "yaml"): com.dialex.domain.model.PersonaDNA =
        wrap { client.importPersonaDna(content, format) }
    suspend fun exportPersonaDna(id: String, format: String = "yaml"): String =
        wrap { client.exportPersonaDna(id, format) }

    // ── Socratic Interview ─────────────────────────────────────────────────────

    suspend fun socraticTurn(discussionId: String, request: SocraticTurnRequest): SocraticTurnResponse =
        wrap { client.socraticTurn(discussionId, request) }

    suspend fun socraticDigest(discussionId: String): SocraticDigest =
        wrap { client.socraticDigest(discussionId) }

    suspend fun socraticElevate(discussionId: String, request: SocraticElevateRequest): ElevateResult =
        wrap { client.socraticElevate(discussionId, request) }


    // ── Settings ──────────────────────────────────────────────────────────────

    suspend fun getSettings(): AppState = wrap { client.getSettings() }
    suspend fun updateSettings(state: AppState) = wrap { client.updateSettings(state) }
    suspend fun getAvailableModels(): Map<String, List<String>> =
        wrap { client.getAvailableModels() }
    suspend fun getCliStatus(): Map<String, Boolean> =
        wrap { client.getCliStatus() }
    suspend fun getCliLogins(): Map<String, Boolean> =
        wrap { client.getCliLogins() }

    // ── Knowledge Graph ───────────────────────────────────────────────────────

    suspend fun getActiveGraph(projectId: String, minWeight: Double = 0.1): com.dialex.domain.model.KnowledgeGraph =
        wrap { client.getActiveGraph(projectId, minWeight) }

    suspend fun searchGraphNodes(projectId: String, query: String, limit: Int = 10): List<com.dialex.domain.model.KnowledgeNode> =
        wrap { client.searchGraphNodes(projectId, query, limit) }

    suspend fun upsertGraphNode(projectId: String, node: com.dialex.domain.model.KnowledgeNode): com.dialex.domain.model.KnowledgeNode =
        wrap { client.upsertGraphNode(projectId, node) }

    suspend fun deleteGraphNode(nodeId: String) =
        wrap { client.deleteGraphNode(nodeId) }

    suspend fun upsertGraphEdge(projectId: String, edge: com.dialex.domain.model.KnowledgeEdge): com.dialex.domain.model.KnowledgeEdge =
        wrap { client.upsertGraphEdge(projectId, edge) }

    suspend fun triggerGraphDecay(minThreshold: Double = 0.05, maxStaleDays: Int = 180): Long =
        wrap { client.triggerGraphDecay(minThreshold, maxStaleDays) }

    // ── Problem Decomposition ────────────────────────────────────────────────

    suspend fun decomposeProblem(
        topic: String,
        context: String = "",
        model: String? = null,
        provider: String? = null
    ): com.dialex.domain.model.ProblemDecomposition =
        wrap { client.decomposeProblem(topic, context, model, provider) }

    // ── Null Hypothesis Benchmarks ─────────────────────────────────────────────

    suspend fun listBenchmarkCases(): List<com.dialex.domain.model.BenchmarkCase> =
        wrap { client.listBenchmarkCases() }

    suspend fun createBenchmarkCase(case: com.dialex.domain.model.BenchmarkCase): com.dialex.domain.model.BenchmarkCase =
        wrap { client.createBenchmarkCase(case) }

    suspend fun runBenchmark(request: com.dialex.domain.model.RunBenchmarkRequest): com.dialex.domain.model.BenchmarkRun =
        wrap { client.runBenchmark(request) }

    suspend fun listBenchmarkRuns(): List<com.dialex.domain.model.BenchmarkRun> =
        wrap { client.listBenchmarkRuns() }

    suspend fun getBenchmarkRun(id: String): com.dialex.domain.model.BenchmarkRun =
        wrap { client.getBenchmarkRun(id) }

    suspend fun getBenchmarkSummary(): com.dialex.domain.model.BenchmarkSummary =
        wrap { client.getBenchmarkSummary() }

    suspend fun exportBenchmarks(format: String = "markdown"): String =
        wrap { client.exportBenchmarks(format) }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Executes [block] and maps any Ktor/Engine exception to a typed [DomainException].
     * Only this class should catch EngineException/ClientRequestException/IOException —
     * everything above rethrows the already-typed DomainException.
     */
    private suspend fun <T> wrap(block: suspend () -> T): T = try {
        block()
    } catch (e: EngineException) {
        val detail = e.message ?: "Status ${e.status}"
        com.dialex.logging.AppLogStore.error("EngineRPC", "Engine request failed (${e.status}): $detail", e)
        throw when (e.status) {
            401 -> DomainException.Unauthorized(e)
            404 -> DomainException.NotFound(cause = e)
            422, 400 -> DomainException.InvalidRequest(detail, e)
            else -> DomainException.Unknown(detail, e)
        }
    } catch (e: ClientRequestException) {
        com.dialex.logging.AppLogStore.error("EngineRPC", "Engine request failed (${e.response.status}): ${e.message}", e)
        throw when (e.response.status) {
            HttpStatusCode.Unauthorized -> DomainException.Unauthorized(e)
            HttpStatusCode.NotFound -> DomainException.NotFound(cause = e)
            HttpStatusCode.UnprocessableEntity,
            HttpStatusCode.BadRequest -> DomainException.InvalidRequest(
                e.response.status.description, e
            )
            else -> DomainException.Unknown(e.message, e)
        }
    } catch (e: Exception) {
        val msg = e.message ?: ""
        val isNetworkRefusal = msg.contains("connect", ignoreCase = true) ||
            msg.contains("refused", ignoreCase = true) ||
            msg.contains("timeout", ignoreCase = true)

        if (isNetworkRefusal) {
            com.dialex.logging.AppLogStore.warn("EngineRPC", "Engine network offline or connection refused: $msg")
            throw DomainException.NoConnectivity(e)
        } else {
            com.dialex.logging.AppLogStore.error("EngineRPC", "Engine network error: $msg", e)
            throw DomainException.Unknown(msg, e)
        }
    }
}
