package com.dialex.engine

import com.dialex.domain.model.ElevateResult
import com.dialex.domain.model.SocraticDigest
import com.dialex.domain.model.SocraticElevateRequest
import com.dialex.domain.model.SocraticTurnRequest
import com.dialex.domain.model.SocraticTurnResponse
import com.dialex.model.ApiKeys
import com.dialex.model.AppState
import com.dialex.model.CliCommands
import com.dialex.model.CompactionSettings
import com.dialex.model.DebateConfig
import com.dialex.model.DebateMessage
import com.dialex.model.Discussion
import com.dialex.model.PredefinedPersona
import com.dialex.model.Project
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Thrown for a non-2xx engine response. */
class EngineException(val status: Int, message: String) : Exception("Engine returned HTTP $status: $message")

/**
 * Talks to the Go engine's REST + SSE API over HTTP.
 */
class EngineClient(
    private var baseUrl: String,
    private val timeoutMillis: Long = 120_000,
    private val client: HttpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                coerceInputValues = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutMillis
            connectTimeoutMillis = timeoutMillis
        }
    },
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    var token: String? = null
        private set

    var currentUsername: String? = null
        private set

    var fallbackUrl: String? = null
        private set

    val activeBaseUrl: String
        get() = baseUrl

    fun setBaseUrl(url: String) {
        baseUrl = url.removeSuffix("/")
    }

    fun setFallbackUrl(url: String?) {
        fallbackUrl = url?.removeSuffix("/")
    }

    fun setAuthToken(newToken: String, username: String? = null) {
        token = newToken
        if (username != null) {
            currentUsername = username
        }
    }

    /**
     * Pings the active baseUrl; if unreachable and fallbackUrl (e.g. Tailscale MagicDNS) is configured,
     * seamlessly switches baseUrl to the fallback address.
     */
    suspend fun ensureReachable(): Boolean {
        return try {
            health()
            true
        } catch (e: Exception) {
            val fallback = fallbackUrl
            if (!fallback.isNullOrBlank() && fallback != baseUrl) {
                try {
                    val prevBase = baseUrl
                    baseUrl = fallback
                    health()
                    true
                } catch (fallbackEx: Exception) {
                    false
                }
            } else {
                false
            }
        }
    }

    @Serializable
    private data class LoginRequest(val username: String, val password: String)

    @Serializable
    private data class LoginResponse(val token: String)

    private var lastLoginCredentials: Pair<String, String>? = null

    suspend fun login(username: String, password: String) {
        val resp: LoginResponse = post("/auth/login", LoginRequest(username, password), authenticated = false)
        token = resp.token
        currentUsername = username
        lastLoginCredentials = username to password
    }

    suspend fun tryAutoReauth(): Boolean {
        val creds = lastLoginCredentials ?: return false
        return try {
            val response = client.post("$baseUrl/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(LoginRequest(creds.first, creds.second)))
            }
            val text = response.bodyAsText()
            if (!response.status.isSuccess()) {
                com.dialex.logging.AppLogStore.warn("EngineClient", "Auto-reauthentication failed: HTTP ${response.status.value}: $text")
                return false
            }
            val resp = json.decodeFromString<LoginResponse>(text)
            token = resp.token
            currentUsername = creds.first
            com.dialex.logging.AppLogStore.info("EngineClient", "Successfully re-authenticated with engine after token invalidation")
            true
        } catch (e: Exception) {
            com.dialex.logging.AppLogStore.warn("EngineClient", "Auto-reauthentication exception: ${e.message}")
            false
        }
    }

    @Serializable
    data class HealthStatus(val status: String, val time: String)

    suspend fun health(): HealthStatus = get("/health", authenticated = false)

    // ── Projects ──────────────────────────────────────────────────────────────

    suspend fun listProjects(): List<Project> = get("/projects")

    suspend fun createProject(name: String): Project {
        val payload = Project(id = "", name = name)
        return post("/projects", payload)
    }

    suspend fun updateProject(project: Project): Project = put("/projects/${project.id}", project)

    suspend fun deleteProject(id: String) = delete("/projects/$id")

    // ── Debates (Discussions) ──────────────────────────────────────────────────

    suspend fun listDebates(projectId: String? = null): List<Discussion> =
        if (projectId != null) get("/debates?projectId=$projectId") else get("/debates")

    suspend fun createDebate(
        projectId: String,
        name: String,
        config: DebateConfig,
        mode: com.dialex.domain.model.DiscussionMode = com.dialex.domain.model.DiscussionMode.COUNCIL,
        socraticConfig: com.dialex.domain.model.SocraticConfig? = null,
    ): Discussion {
        val payload = Discussion(
            id = "",
            projectId = projectId,
            name = name,
            config = config,
            mode = mode,
            socraticConfig = socraticConfig
        )
        return post("/debates", payload)
    }

    suspend fun getDebate(id: String): Discussion = get("/debates/$id")

    suspend fun updateDebate(discussion: Discussion): Discussion = put("/debates/${discussion.id}", discussion)

    suspend fun deleteDebate(id: String) = delete("/debates/$id")

    suspend fun duplicateDebate(id: String): Discussion = postNoBodyReturn("/api/v1/debates/$id/duplicate")

    suspend fun startDebate(id: String) = postNoBody("/debates/$id/start")
    suspend fun resumeDebate(id: String) = postNoBody("/debates/$id/resume")
    suspend fun pauseDebate(id: String) = postNoBody("/debates/$id/pause")
    suspend fun hardStopDebate(id: String) = postNoBody("/debates/$id/stop")

    @Serializable
    private data class HandoffResponse(val prompt: String)

    suspend fun generateHandoffPrompt(id: String): String =
        post<Unit, HandoffResponse>("/debates/$id/handoff", Unit).prompt

    @Serializable
    private data class DeliverableRequest(val format: String)

    @Serializable
    private data class DeliverableResponse(val content: String)

    suspend fun generateDeliverable(id: String, format: String): String =
        post<DeliverableRequest, DeliverableResponse>("/debates/$id/deliverable", DeliverableRequest(format)).content

    @Serializable
    private data class TitleResponse(val title: String)

    suspend fun generateDebateTitle(id: String): String =
        post<Unit, TitleResponse>("/debates/$id/generate-title", Unit).title

    suspend fun setupDiscussionWithAi(request: com.dialex.domain.model.AiSetupRequest): Discussion =
        post("/api/v1/debates/ai-setup", request)

    // ── Knowledge Graph ───────────────────────────────────────────────────────

    suspend fun getActiveGraph(projectId: String, minWeight: Double = 0.1): com.dialex.domain.model.KnowledgeGraph =
        get("/api/v1/projects/$projectId/graph?min_weight=$minWeight")

    suspend fun searchGraphNodes(projectId: String, query: String, limit: Int = 10): List<com.dialex.domain.model.KnowledgeNode> =
        get("/api/v1/projects/$projectId/graph/search?q=$query&limit=$limit")

    suspend fun upsertGraphNode(projectId: String, node: com.dialex.domain.model.KnowledgeNode): com.dialex.domain.model.KnowledgeNode =
        post("/api/v1/projects/$projectId/graph/nodes", node)

    suspend fun deleteGraphNode(nodeId: String) =
        delete("/api/v1/projects/__any__/graph/nodes/$nodeId")

    suspend fun upsertGraphEdge(projectId: String, edge: com.dialex.domain.model.KnowledgeEdge): com.dialex.domain.model.KnowledgeEdge =
        post("/api/v1/projects/$projectId/graph/edges", edge)

    @Serializable
    private data class DecayResponse(val pruned_count: Long = 0L)

    suspend fun triggerGraphDecay(minThreshold: Double = 0.05, maxStaleDays: Int = 180): Long =
        post<Unit, DecayResponse>("/api/v1/graph/decay", Unit).pruned_count

    // ── Problem Decomposition ────────────────────────────────────────────────
    suspend fun decomposeProblem(
        topic: String,
        context: String = "",
        model: String? = null,
        provider: String? = null
    ): com.dialex.domain.model.ProblemDecomposition =
        post(
            "/api/v1/discussions/decompose",
            com.dialex.domain.model.DecompositionRequest(
                topic = topic,
                context = context,
                model = model,
                provider = provider
            )
        )

    fun streamDebate(id: String): Flow<Discussion> = flow {
        var retried = false
        while (true) {
            var needRetry = false
            client.prepareGet("$baseUrl/debates/$id/stream") {
                token?.let { header("Authorization", "Bearer $it") }
            }.execute { response ->
                if (response.status.value == 401 && !retried && tryAutoReauth()) {
                    needRetry = true
                    retried = true
                    return@execute
                }
                if (!response.status.isSuccess()) {
                    throw EngineException(response.status.value, response.bodyAsText())
                }
                val channel = response.bodyAsChannel()
                var eventName: String? = null
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    when {
                        line.startsWith("event: ") -> eventName = line.removePrefix("event: ").trim()
                        line.startsWith("data: ") -> {
                            val data = line.removePrefix("data: ").trim()
                            if (eventName == null || eventName == "snapshot" || eventName == "turn" || eventName == "message") {
                                val disc = runCatching { json.decodeFromString<Discussion>(data) }.getOrNull()
                                if (disc != null) emit(disc)
                            }
                            eventName = null
                        }
                    }
                }
            }
            if (!needRetry) break
        }
    }

    @Serializable
    data class UsageResponse(
        val tokensIn: Long = 0L,
        val tokensOut: Long = 0L,
        val estimatedCostUsd: Double = 0.0,
    )

    suspend fun getDiscussionUsage(id: String): UsageResponse =
        runCatching { get<UsageResponse>("/api/v1/debates/$id/usage") }.getOrDefault(UsageResponse())

    suspend fun getProjectUsage(projectId: String): UsageResponse =
        runCatching { get<UsageResponse>("/api/v1/projects/$projectId/usage") }.getOrDefault(UsageResponse())

    // ── Personas ──────────────────────────────────────────────────────────────

    suspend fun listPersonas(): List<PredefinedPersona> =
        runCatching { get<List<PredefinedPersona>>("/api/v1/personas") }.getOrDefault(emptyList())

    suspend fun createPersona(persona: PredefinedPersona): PredefinedPersona =
        post("/api/v1/personas", persona)

    suspend fun deletePersona(id: String) = delete("/api/v1/personas/$id")

    @Serializable
    private data class ImportResponse(val imported: Int)

    suspend fun importPersonas(jsonString: String): Int {
        val personas = json.decodeFromString<List<PredefinedPersona>>(jsonString)
        val resp: ImportResponse = post("/api/v1/personas/import", personas)
        return resp.imported
    }

    suspend fun exportPersonas(): String {
        val list = get<List<PredefinedPersona>>("/api/v1/personas/export")
        return json.encodeToString(list)
    }

    suspend fun chatPersona(request: com.dialex.domain.model.PersonaChatRequest): com.dialex.domain.model.PersonaChatResponse =
        post("/api/v1/personas/chat", request)

    // ── Socratic Interview ─────────────────────────────────────────────────────

    suspend fun socraticTurn(discussionId: String, request: SocraticTurnRequest): SocraticTurnResponse =
        post("/api/v1/discussions/$discussionId/socratic/turn", request)

    suspend fun socraticDigest(discussionId: String): SocraticDigest =
        post("/api/v1/discussions/$discussionId/socratic/digest", emptyMap<String, String>())

    suspend fun socraticElevate(discussionId: String, request: SocraticElevateRequest): ElevateResult =
        post("/api/v1/discussions/$discussionId/socratic/elevate", request)


    // ── Settings ──────────────────────────────────────────────────────────────

    suspend fun getSettings(): AppState = get("/settings")

    suspend fun updateSettings(state: AppState) {
        put<AppState, Unit>("/settings", state)
    }

    suspend fun getAvailableModels(): Map<String, List<String>> =
        runCatching { get<Map<String, List<String>>>("/api/v1/models") }.getOrDefault(emptyMap())

    suspend fun getCliStatus(): Map<String, Boolean> =
        runCatching { get<Map<String, Boolean>>("/api/v1/cli/status") }.getOrDefault(emptyMap())

    suspend fun getCliLogins(): Map<String, Boolean> =
        runCatching { get<Map<String, Boolean>>("/api/v1/cli/logins") }.getOrDefault(emptyMap())

    // ── Low-level helpers ──────────────────────────────────────────────────────

    private fun HttpRequestBuilder.authenticate() {
        token?.let { header("Authorization", "Bearer $it") }
    }

    private suspend fun requireSuccess(response: HttpResponse): String {
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw EngineException(response.status.value, text)
        }
        return text
    }

    private suspend inline fun <reified T> get(path: String, authenticated: Boolean = true): T {
        val start = System.currentTimeMillis()
        val fullUrl = "$baseUrl$path"
        var statusCode: Int? = null
        var resBody: String? = null
        var errorMsg: String? = null
        var success = false
        try {
            var response = client.get(fullUrl) { if (authenticated) authenticate() }
            if (response.status.value == 401 && authenticated && tryAutoReauth()) {
                response = client.get(fullUrl) { authenticate() }
            }
            statusCode = response.status.value
            resBody = requireSuccess(response)
            success = true
            return json.decodeFromString(resBody)
        } catch (e: Exception) {
            errorMsg = e.message ?: e.toString()
            throw e
        } finally {
            val duration = System.currentTimeMillis() - start
            val reqHeaders = buildMap {
                if (authenticated && token != null) put("Authorization", "Bearer $token")
            }
            com.dialex.logging.ApiCallStore.trackEngineCall(
                method = "GET",
                url = fullUrl,
                requestHeaders = reqHeaders,
                requestBody = null,
                responseStatusCode = statusCode,
                responseHeaders = emptyMap(),
                responseBody = resBody ?: errorMsg,
                errorDetails = errorMsg,
                durationMs = duration,
                isSuccess = success
            )
        }
    }

    private suspend inline fun <reified TBody, reified TResponse> post(
        path: String,
        body: TBody,
        authenticated: Boolean = true,
    ): TResponse {
        val start = System.currentTimeMillis()
        val fullUrl = "$baseUrl$path"
        val bodyJson = json.encodeToString(body)
        var statusCode: Int? = null
        var resBody: String? = null
        var errorMsg: String? = null
        var success = false
        try {
            var response = client.post(fullUrl) {
                if (authenticated) authenticate()
                contentType(ContentType.Application.Json)
                setBody(bodyJson)
            }
            if (response.status.value == 401 && authenticated && tryAutoReauth()) {
                response = client.post(fullUrl) {
                    authenticate()
                    contentType(ContentType.Application.Json)
                    setBody(bodyJson)
                }
            }
            statusCode = response.status.value
            resBody = requireSuccess(response)
            success = true
            return json.decodeFromString(resBody)
        } catch (e: Exception) {
            errorMsg = e.message ?: e.toString()
            throw e
        } finally {
            val duration = System.currentTimeMillis() - start
            val reqHeaders = buildMap {
                put("Content-Type", "application/json")
                if (authenticated && token != null) put("Authorization", "Bearer $token")
            }
            com.dialex.logging.ApiCallStore.trackEngineCall(
                method = "POST",
                url = fullUrl,
                requestHeaders = reqHeaders,
                requestBody = bodyJson,
                responseStatusCode = statusCode,
                responseHeaders = emptyMap(),
                responseBody = resBody ?: errorMsg,
                errorDetails = errorMsg,
                durationMs = duration,
                isSuccess = success
            )
        }
    }

    private suspend fun postNoBody(path: String) {
        val start = System.currentTimeMillis()
        val fullUrl = "$baseUrl$path"
        var statusCode: Int? = null
        var resBody: String? = null
        var errorMsg: String? = null
        var success = false
        try {
            var response = client.post(fullUrl) { authenticate() }
            if (response.status.value == 401 && tryAutoReauth()) {
                response = client.post(fullUrl) { authenticate() }
            }
            statusCode = response.status.value
            resBody = requireSuccess(response)
            success = true
        } catch (e: Exception) {
            errorMsg = e.message ?: e.toString()
            throw e
        } finally {
            val duration = System.currentTimeMillis() - start
            val reqHeaders = buildMap {
                if (token != null) put("Authorization", "Bearer $token")
            }
            com.dialex.logging.ApiCallStore.trackEngineCall(
                method = "POST",
                url = fullUrl,
                requestHeaders = reqHeaders,
                requestBody = null,
                responseStatusCode = statusCode,
                responseHeaders = emptyMap(),
                responseBody = resBody ?: errorMsg,
                errorDetails = errorMsg,
                durationMs = duration,
                isSuccess = success
            )
        }
    }

    private suspend inline fun <reified TResponse> postNoBodyReturn(path: String): TResponse {
        val start = System.currentTimeMillis()
        val fullUrl = "$baseUrl$path"
        var statusCode: Int? = null
        var resBody: String? = null
        var errorMsg: String? = null
        var success = false
        try {
            var response = client.post(fullUrl) { authenticate() }
            if (response.status.value == 401 && tryAutoReauth()) {
                response = client.post(fullUrl) { authenticate() }
            }
            statusCode = response.status.value
            resBody = requireSuccess(response)
            success = true
            return json.decodeFromString(resBody)
        } catch (e: Exception) {
            errorMsg = e.message ?: e.toString()
            throw e
        } finally {
            val duration = System.currentTimeMillis() - start
            val reqHeaders = buildMap {
                if (token != null) put("Authorization", "Bearer $token")
            }
            com.dialex.logging.ApiCallStore.trackEngineCall(
                method = "POST",
                url = fullUrl,
                requestHeaders = reqHeaders,
                requestBody = null,
                responseStatusCode = statusCode,
                responseHeaders = emptyMap(),
                responseBody = resBody ?: errorMsg,
                errorDetails = errorMsg,
                durationMs = duration,
                isSuccess = success
            )
        }
    }

    private suspend inline fun <reified TBody, reified TResponse> put(path: String, body: TBody): TResponse {
        val start = System.currentTimeMillis()
        val fullUrl = "$baseUrl$path"
        val bodyJson = json.encodeToString(body)
        var statusCode: Int? = null
        var resBody: String? = null
        var errorMsg: String? = null
        var success = false
        try {
            var response = client.put(fullUrl) {
                authenticate()
                contentType(ContentType.Application.Json)
                setBody(bodyJson)
            }
            if (response.status.value == 401 && tryAutoReauth()) {
                response = client.put(fullUrl) {
                    authenticate()
                    contentType(ContentType.Application.Json)
                    setBody(bodyJson)
                }
            }
            statusCode = response.status.value
            resBody = requireSuccess(response)
            success = true
            return if (TResponse::class == Unit::class) {
                Unit as TResponse
            } else {
                json.decodeFromString(resBody)
            }
        } catch (e: Exception) {
            errorMsg = e.message ?: e.toString()
            throw e
        } finally {
            val duration = System.currentTimeMillis() - start
            val reqHeaders = buildMap {
                put("Content-Type", "application/json")
                if (token != null) put("Authorization", "Bearer $token")
            }
            com.dialex.logging.ApiCallStore.trackEngineCall(
                method = "PUT",
                url = fullUrl,
                requestHeaders = reqHeaders,
                requestBody = bodyJson,
                responseStatusCode = statusCode,
                responseHeaders = emptyMap(),
                responseBody = resBody ?: errorMsg,
                errorDetails = errorMsg,
                durationMs = duration,
                isSuccess = success
            )
        }
    }

    private suspend fun delete(path: String) {
        val start = System.currentTimeMillis()
        val fullUrl = "$baseUrl$path"
        var statusCode: Int? = null
        var resBody: String? = null
        var errorMsg: String? = null
        var success = false
        try {
            var response = client.delete(fullUrl) { authenticate() }
            if (response.status.value == 401 && tryAutoReauth()) {
                response = client.delete(fullUrl) { authenticate() }
            }
            statusCode = response.status.value
            resBody = requireSuccess(response)
            success = true
        } catch (e: Exception) {
            errorMsg = e.message ?: e.toString()
            throw e
        } finally {
            val duration = System.currentTimeMillis() - start
            val reqHeaders = buildMap {
                if (token != null) put("Authorization", "Bearer $token")
            }
            com.dialex.logging.ApiCallStore.trackEngineCall(
                method = "DELETE",
                url = fullUrl,
                requestHeaders = reqHeaders,
                requestBody = null,
                responseStatusCode = statusCode,
                responseHeaders = emptyMap(),
                responseBody = resBody ?: errorMsg,
                errorDetails = errorMsg,
                durationMs = duration,
                isSuccess = success
            )
        }
    }
}
