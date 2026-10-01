package com.dialex.runner

import com.dialex.model.Agent
import com.dialex.model.DebateMessage
import com.dialex.model.Provider
import com.dialex.model.isFrom
import com.dialex.model.speaker
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import kotlinx.coroutines.delay
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Calls each provider's REST API directly with a key from the global Settings screen (one
 * key per provider — every API-mode agent on that provider shares it). One HTTP call per
 * turn, full transcript sent as conversation history every time (no server-side session).
 */
class ApiAgentRunner(
    private val apiKeys: Map<Provider, String>,
    // Matches CliAgentRunner's default timeout — previously unset here, so a hung provider
    // call could block a turn indefinitely with no feedback.
    timeoutMillis: Long = 120_000,
    private val client: HttpClient = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutMillis
            connectTimeoutMillis = timeoutMillis
        }
    },
) : AgentRunner {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun respond(
        agent: Agent,
        topic: String,
        commonContext: String,
        commonInstructions: String,
        transcript: List<DebateMessage>,
        modelOverride: String?,
    ): AgentReply {
        val key = if (agent.provider == Provider.OLLAMA) {
            apiKeys[agent.provider]?.ifBlank { "http://localhost:11434" } ?: "http://localhost:11434"
        } else {
            apiKeys[agent.provider]?.takeIf { it.isNotBlank() }
                ?: error("No API key set for ${agent.provider} in Settings")
        }
        val sharedSystem = buildString {
            if (topic.isNotBlank()) appendLine("Topic: $topic")
            if (commonContext.isNotBlank()) appendLine("Context: $commonContext")
            if (commonInstructions.isNotBlank()) appendLine("Rules: $commonInstructions")
        }.trim()
        val agentSystem = buildString {
            if (agent.context.isNotBlank()) appendLine(agent.context)
            if (agent.systemPrompt.isNotBlank()) appendLine(agent.systemPrompt)
        }.trim()
        val fullSystem = when {
            sharedSystem.isNotBlank() && agentSystem.isNotBlank() -> "$sharedSystem\n$agentSystem"
            else -> sharedSystem.ifBlank { agentSystem }
        }
        val effectiveAgent = if (modelOverride != null) agent.copy(model = modelOverride) else agent
        return when (agent.provider) {
            Provider.ANTHROPIC -> callAnthropic(effectiveAgent, key, sharedSystem, agentSystem, transcript)
            // xAI, DeepSeek, and Mistral all expose an OpenAI-compatible /chat/completions
            // endpoint — one implementation, just a different base URL and bearer key per
            // provider, instead of three more bespoke request/response parsers.
            Provider.OPENAI -> callOpenAiCompatible(effectiveAgent, key, fullSystem, transcript, "https://api.openai.com/v1/chat/completions")
            Provider.GROK -> callOpenAiCompatible(effectiveAgent, key, fullSystem, transcript, "https://api.x.ai/v1/chat/completions")
            Provider.DEEPSEEK -> callOpenAiCompatible(effectiveAgent, key, fullSystem, transcript, "https://api.deepseek.com/chat/completions")
            Provider.MISTRAL -> callOpenAiCompatible(effectiveAgent, key, fullSystem, transcript, "https://api.mistral.ai/v1/chat/completions")
            Provider.GEMINI -> callGemini(effectiveAgent, key, fullSystem, transcript)
            Provider.OLLAMA -> callOllama(effectiveAgent, key, fullSystem, transcript)
            // No fixed API shape for an arbitrary CLI tool — CUSTOM is CLI-only, the UI
            // never lets it reach API mode (forced back to CLI on selection).
            Provider.CUSTOM -> error("CUSTOM has no API mode — this agent should be CLI-only")
        }
    }

    /** Providers put usage counts under a different key/shape each — missing/malformed is
     * just "unknown", not an error, so this never throws. */
    private fun JsonObject.intOrNull(key: String): Int? = this[key]?.jsonPrimitive?.int

    /** A non-2xx response's body doesn't match the success shape any of the three parsers
     * below expect — reading it as one anyway used to fail with an opaque NPE ("content"/
     * "choices"/"candidates" simply isn't there on an error body). Surfacing the actual
     * status and error text here means "bad API key" reads as "bad API key", not a stack
     * trace. */
    private suspend fun HttpResponse.requireSuccess(): HttpResponse {
        if (!status.isSuccess()) {
            val body = runCatching { bodyAsText() }.getOrDefault("").take(500).ifBlank { "(no response body)" }
            error("HTTP ${status.value}: $body")
        }
        return this
    }

    private suspend fun executeWithQuotaRetry(
        block: suspend () -> HttpResponse
    ): HttpResponse {
        var attempts = 0
        val maxQuotaRetries = 3
        while (true) {
            val resp = block()
            if (resp.status.value == 429) {
                attempts++
                if (attempts > maxQuotaRetries) {
                    return resp
                }
                val retryAfterSec = resp.headers["Retry-After"]?.toLongOrNull()
                    ?: (resp.headers["retry-after-ms"]?.toLongOrNull()?.let { it / 1000 })
                    ?: (attempts * 3L)
                val delayMs = (retryAfterSec * 1000L).coerceIn(1000L, 30_000L)
                delay(delayMs)
                continue
            }
            return resp
        }
    }

    private suspend fun callAnthropic(agent: Agent, key: String, sharedSystem: String, agentSystem: String, transcript: List<DebateMessage>): AgentReply {
        val start = System.currentTimeMillis()
        val url = "https://api.anthropic.com/v1/messages"
        val cacheControl = buildJsonObject { put("type", "ephemeral") }
        val messages = buildJsonArray {
            transcript.forEachIndexed { i, m ->
                add(buildJsonObject {
                    put("role", if (m.isFrom(agent)) "assistant" else "user")
                    if (i == transcript.lastIndex) {
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", "[${m.speaker()}] ${m.content}")
                                put("cache_control", cacheControl)
                            })
                        })
                    } else {
                        put("content", "[${m.speaker()}] ${m.content}")
                    }
                })
            }
        }
        val systemBlocks = buildJsonArray {
            if (sharedSystem.isNotBlank()) {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", sharedSystem)
                    put("cache_control", cacheControl)
                })
            }
            if (agentSystem.isNotBlank()) {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", agentSystem)
                })
            }
        }
        val body = buildJsonObject {
            put("model", agent.model)
            put("max_tokens", agent.maxTokens ?: 4096)
            if (agent.temperature != null) {
                put("temperature", agent.temperature.coerceIn(0.0, 1.0))
            }
            if (agent.topP != null) {
                put("top_p", agent.topP.coerceIn(0.0, 1.0))
            }
            put("system", systemBlocks)
            put("messages", messages)
        }
        val bodyStr = body.toString()
        val reqHeaders = mapOf(
            "x-api-key" to key,
            "anthropic-version" to "2023-06-01",
            "content-type" to "application/json"
        )
        var statusCode: Int? = null
        var respBodyStr: String? = null
        var errorMsg: String? = null
        var inTokens: Int? = null
        var outTokens: Int? = null
        var cachedTokens: Int? = null

        try {
            val resp: HttpResponse = executeWithQuotaRetry {
                client.post(url) {
                    headers {
                        append("x-api-key", key)
                        append("anthropic-version", "2023-06-01")
                    }
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
            statusCode = resp.status.value
            respBodyStr = resp.bodyAsText()
            if (!resp.status.isSuccess()) {
                error("HTTP ${resp.status.value}: ${respBodyStr.take(500)}")
            }
            val jsonResp: JsonObject = json.decodeFromString(respBodyStr)
            val text = jsonResp["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
            val usage = jsonResp["usage"]?.jsonObject
            inTokens = usage?.intOrNull("input_tokens")
            outTokens = usage?.intOrNull("output_tokens")
            cachedTokens = usage?.intOrNull("cache_read_input_tokens")
            return AgentReply(text, inTokens, outTokens, cachedTokens)
        } catch (e: Exception) {
            errorMsg = e.message ?: e.toString()
            throw e
        } finally {
            val duration = System.currentTimeMillis() - start
            com.dialex.logging.ApiCallStore.trackAgentApiCall(
                provider = agent.provider,
                model = agent.model,
                method = "POST",
                url = url,
                requestHeaders = reqHeaders,
                requestBody = bodyStr,
                responseStatusCode = statusCode,
                responseHeaders = emptyMap(),
                responseBody = respBodyStr ?: errorMsg,
                errorDetails = errorMsg,
                durationMs = duration,
                isSuccess = errorMsg == null,
                tokensIn = inTokens,
                tokensOut = outTokens,
                tokensCached = cachedTokens
            )
        }
    }

    /** Shared shape for every provider whose API is an OpenAI-compatible
     * `/chat/completions` endpoint (OpenAI itself, xAI, DeepSeek, Mistral) — same request
     * body, same response parsing, only the URL and key differ. */
    private suspend fun callOpenAiCompatible(agent: Agent, key: String, system: String, transcript: List<DebateMessage>, url: String): AgentReply {
        val start = System.currentTimeMillis()
        val messages = buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", system) })
            transcript.forEach { m ->
                add(buildJsonObject {
                    put("role", if (m.isFrom(agent)) "assistant" else "user")
                    put("content", "[${m.speaker()}] ${m.content}")
                })
            }
        }
        val body = buildJsonObject {
            put("model", agent.model)
            if (agent.temperature != null) {
                put("temperature", agent.temperature)
            }
            if (agent.topP != null) {
                put("top_p", agent.topP)
            }
            if (agent.frequencyPenalty != null) {
                put("frequency_penalty", agent.frequencyPenalty)
            }
            if (agent.presencePenalty != null) {
                put("presence_penalty", agent.presencePenalty)
            }
            if (agent.maxTokens != null) {
                put("max_tokens", agent.maxTokens)
            }
            put("messages", messages)
        }
        val bodyStr = body.toString()
        val reqHeaders = mapOf(
            "Authorization" to "Bearer $key",
            "content-type" to "application/json"
        )
        var statusCode: Int? = null
        var respBodyStr: String? = null
        var errorMsg: String? = null
        var inTokens: Int? = null
        var outTokens: Int? = null
        var cachedTokens: Int? = null

        try {
            val resp: HttpResponse = executeWithQuotaRetry {
                client.post(url) {
                    headers { append(HttpHeaders.Authorization, "Bearer $key") }
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
            statusCode = resp.status.value
            respBodyStr = resp.bodyAsText()
            if (!resp.status.isSuccess()) {
                error("HTTP ${resp.status.value}: ${respBodyStr.take(500)}")
            }
            val jsonResp: JsonObject = json.decodeFromString(respBodyStr)
            val text = jsonResp["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
            val usage = jsonResp["usage"]?.jsonObject
            inTokens = usage?.intOrNull("prompt_tokens")
            outTokens = usage?.intOrNull("completion_tokens")
            cachedTokens = usage?.get("prompt_tokens_details")?.jsonObject?.intOrNull("cached_tokens")
            return AgentReply(text, inTokens, outTokens, cachedTokens)
        } catch (e: Exception) {
            errorMsg = e.message ?: e.toString()
            throw e
        } finally {
            val duration = System.currentTimeMillis() - start
            com.dialex.logging.ApiCallStore.trackAgentApiCall(
                provider = agent.provider,
                model = agent.model,
                method = "POST",
                url = url,
                requestHeaders = reqHeaders,
                requestBody = bodyStr,
                responseStatusCode = statusCode,
                responseHeaders = emptyMap(),
                responseBody = respBodyStr ?: errorMsg,
                errorDetails = errorMsg,
                durationMs = duration,
                isSuccess = errorMsg == null,
                tokensIn = inTokens,
                tokensOut = outTokens,
                tokensCached = cachedTokens
            )
        }
    }

    private suspend fun callGemini(agent: Agent, key: String, system: String, transcript: List<DebateMessage>): AgentReply {
        val start = System.currentTimeMillis()
        val contents = buildJsonArray {
            transcript.forEach { m ->
                add(buildJsonObject {
                    put("role", if (m.isFrom(agent)) "model" else "user")
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", "[${m.speaker()}] ${m.content}") }) })
                })
            }
        }
        val body = buildJsonObject {
            put("systemInstruction", buildJsonObject { put("parts", buildJsonArray { add(buildJsonObject { put("text", system) }) }) })
            put("contents", contents)
            if (agent.temperature != null || agent.topP != null || agent.maxTokens != null) {
                put("generationConfig", buildJsonObject {
                    if (agent.temperature != null) put("temperature", agent.temperature)
                    if (agent.topP != null) put("topP", agent.topP)
                    if (agent.maxTokens != null) put("maxOutputTokens", agent.maxTokens)
                })
            }
        }
        val bodyStr = body.toString()
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${agent.model}:generateContent?key=$key"
        val reqHeaders = mapOf("content-type" to "application/json")
        var statusCode: Int? = null
        var respBodyStr: String? = null
        var errorMsg: String? = null
        var inTokens: Int? = null
        var outTokens: Int? = null
        var cachedTokens: Int? = null

        try {
            val resp: HttpResponse = executeWithQuotaRetry {
                client.post(url) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
            statusCode = resp.status.value
            respBodyStr = resp.bodyAsText()
            if (!resp.status.isSuccess()) {
                error("HTTP ${resp.status.value}: ${respBodyStr.take(500)}")
            }
            val jsonResp: JsonObject = json.decodeFromString(respBodyStr)
            val text = jsonResp["candidates"]!!.jsonArray[0].jsonObject["content"]!!.jsonObject["parts"]!!
                .jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
            val usage = jsonResp["usageMetadata"]?.jsonObject
            inTokens = usage?.intOrNull("promptTokenCount")
            outTokens = usage?.intOrNull("candidatesTokenCount")
            cachedTokens = usage?.intOrNull("cachedContentTokenCount")
            return AgentReply(text, inTokens, outTokens, cachedTokens)
        } catch (e: Exception) {
            errorMsg = e.message ?: e.toString()
            throw e
        } finally {
            val duration = System.currentTimeMillis() - start
            com.dialex.logging.ApiCallStore.trackAgentApiCall(
                provider = agent.provider,
                model = agent.model,
                method = "POST",
                url = url,
                requestHeaders = reqHeaders,
                requestBody = bodyStr,
                responseStatusCode = statusCode,
                responseHeaders = emptyMap(),
                responseBody = respBodyStr ?: errorMsg,
                errorDetails = errorMsg,
                durationMs = duration,
                isSuccess = errorMsg == null,
                tokensIn = inTokens,
                tokensOut = outTokens,
                tokensCached = cachedTokens
            )
        }
    }

    private suspend fun callOllama(
        agent: Agent,
        rawEndpoint: String,
        system: String,
        transcript: List<DebateMessage>
    ): AgentReply {
        val start = System.currentTimeMillis()
        val endpoint = rawEndpoint.trim().trimEnd('/').ifBlank { "http://localhost:11434" }
        val url = if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) {
            "$endpoint/api/chat"
        } else {
            "http://$endpoint/api/chat"
        }

        val messages = buildJsonArray {
            if (system.isNotBlank()) {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", system)
                })
            }
            transcript.forEach { m ->
                add(buildJsonObject {
                    put("role", if (m.isFrom(agent)) "assistant" else "user")
                    put("content", "[${m.speaker()}] ${m.content}")
                })
            }
        }

        val options = buildJsonObject {
            if (agent.temperature != null) put("temperature", agent.temperature)
            if (agent.topP != null) put("top_p", agent.topP)
            if (agent.frequencyPenalty != null) put("repeat_penalty", (1.0 + agent.frequencyPenalty.coerceAtLeast(0.0) * 0.5))
            if (agent.presencePenalty != null) put("presence_penalty", agent.presencePenalty)
            if (agent.maxTokens != null) put("num_predict", agent.maxTokens)
        }

        val body = buildJsonObject {
            put("model", agent.model)
            put("messages", messages)
            put("stream", false)
            if (options.isNotEmpty()) {
                put("options", options)
            }
        }
        val bodyStr = body.toString()
        val reqHeaders = mapOf("content-type" to "application/json")
        var statusCode: Int? = null
        var respBodyStr: String? = null
        var errorMsg: String? = null
        var inTokens: Int? = null
        var outTokens: Int? = null

        try {
            val resp: HttpResponse = executeWithQuotaRetry {
                client.post(url) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
            statusCode = resp.status.value
            respBodyStr = resp.bodyAsText()
            if (!resp.status.isSuccess()) {
                error("HTTP ${resp.status.value}: ${respBodyStr.take(500)}")
            }
            val jsonResp: JsonObject = json.decodeFromString(respBodyStr)
            val text = jsonResp["message"]?.jsonObject?.get("content")?.jsonPrimitive?.content ?: ""
            inTokens = jsonResp["prompt_eval_count"]?.jsonPrimitive?.int
            outTokens = jsonResp["eval_count"]?.jsonPrimitive?.int
            return AgentReply(text, inTokens, outTokens, null)
        } catch (e: Exception) {
            errorMsg = e.message ?: e.toString()
            throw e
        } finally {
            val duration = System.currentTimeMillis() - start
            com.dialex.logging.ApiCallStore.trackAgentApiCall(
                provider = agent.provider,
                model = agent.model,
                method = "POST",
                url = url,
                requestHeaders = reqHeaders,
                requestBody = bodyStr,
                responseStatusCode = statusCode,
                responseHeaders = emptyMap(),
                responseBody = respBodyStr ?: errorMsg,
                errorDetails = errorMsg,
                durationMs = duration,
                isSuccess = errorMsg == null,
                tokensIn = inTokens,
                tokensOut = outTokens,
                tokensCached = null
            )
        }
    }
}
