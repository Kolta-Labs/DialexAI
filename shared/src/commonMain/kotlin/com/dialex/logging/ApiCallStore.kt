package com.dialex.logging

import com.dialex.model.Provider
import com.dialex.util.formatMessageTimestamp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

enum class ApiCallType(val label: String) {
    ENGINE_API("Engine API"),
    AGENT_API("Agent API"),
    CLI_RUNNER("CLI Runner")
}

enum class ApiCallStatus(val label: String) {
    SUCCESS("Success"),
    ERROR("Error"),
    RUNNING("Running"),
    CANCELLED("Cancelled")
}

data class ApiCallRecord(
    val id: String,
    val timestampMs: Long,
    val type: ApiCallType,
    val name: String,
    val method: String,
    val urlOrCommand: String,
    val provider: Provider? = null,
    val model: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    val requestBody: String? = null,
    val requestContentType: String? = null,
    val responseStatusCode: Int? = null,
    val responseHeaders: Map<String, String> = emptyMap(),
    val responseBody: String? = null,
    val errorDetails: String? = null,
    val durationMs: Long = 0L,
    val status: ApiCallStatus = ApiCallStatus.SUCCESS,
    val tokensIn: Int? = null,
    val tokensOut: Int? = null,
    val tokensCached: Int? = null,
    val estimatedCostUsd: Double? = null,
    val requestSizeBytes: Long = requestBody?.encodeToByteArray()?.size?.toLong() ?: 0L,
    val responseSizeBytes: Long = responseBody?.encodeToByteArray()?.size?.toLong() ?: 0L,
    val isEngineCall: Boolean = type == ApiCallType.ENGINE_API,
) {
    fun formatTimestamp(): String = formatMessageTimestamp(timestampMs)

    fun formatDuration(): String = when {
        durationMs < 1000 -> "${durationMs}ms"
        else -> {
            val seconds = durationMs / 1000.0
            val formatted = ((seconds * 10).toLong() / 10.0).toString()
            "${formatted}s"
        }
    }

    fun formatSize(bytes: Long): String = when {
        bytes <= 0 -> "0 B"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> {
            val mb = bytes / (1024.0 * 1024.0)
            val formatted = ((mb * 10).toLong() / 10.0).toString()
            "${formatted} MB"
        }
    }

    fun formatTotalTokens(): String? {
        val total = (tokensIn ?: 0) + (tokensOut ?: 0)
        if (total <= 0) return null
        return if (total >= 1000) "${total / 1000}k tok" else "$total tok"
    }

    fun toCurlCommand(): String {
        if (type == ApiCallType.CLI_RUNNER) {
            val promptEscaped = (requestBody ?: "").replace("'", "'\\''")
            return "echo '$promptEscaped' | $urlOrCommand"
        }
        val builder = StringBuilder("curl -X $method '$urlOrCommand'")
        requestHeaders.forEach { (k, v) ->
            builder.append(" \\\n  -H '$k: $v'")
        }
        if (!requestBody.isNullOrBlank()) {
            val escapedBody = requestBody.replace("'", "'\\''")
            builder.append(" \\\n  --data '$escapedBody'")
        }
        return builder.toString()
    }
}

/**
 * Thread-safe in-memory ring buffer capturing all Agent API calls, CLI process
 * invocations, and Engine REST interactions for the Chucker-style API inspector.
 */
object ApiCallStore {
    private const val MAX_RECORDS = 300
    private val _records = MutableStateFlow<List<ApiCallRecord>>(emptyList())
    val records: StateFlow<List<ApiCallRecord>> = _records.asStateFlow()

    private val prettyJson = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun formatPrettyJson(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val trimmed = raw.trim()
        if (!((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]")))) {
            return raw
        }
        return runCatching {
            val element = prettyJson.parseToJsonElement(trimmed)
            prettyJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), element)
        }.getOrDefault(raw)
    }

    fun sanitizeHeaders(headers: Map<String, String>): Map<String, String> {
        val sensitiveKeys = listOf("authorization", "x-api-key", "api-key", "token", "key")
        return headers.mapValues { (k, v) ->
            if (sensitiveKeys.any { k.contains(it, ignoreCase = true) }) {
                maskSecret(v)
            } else {
                v
            }
        }
    }

    private fun maskSecret(secret: String): String {
        if (secret.length <= 8) return "••••••••"
        return secret.take(4) + "••••" + secret.takeLast(4)
    }

    fun addRecord(record: ApiCallRecord) {
        val current = _records.value
        val updated = if (current.size >= MAX_RECORDS) {
            current.drop(current.size - MAX_RECORDS + 1) + record
        } else {
            current + record
        }
        _records.value = updated
    }

    fun trackEngineCall(
        method: String,
        url: String,
        requestHeaders: Map<String, String>,
        requestBody: String?,
        responseStatusCode: Int?,
        responseHeaders: Map<String, String> = emptyMap(),
        responseBody: String?,
        errorDetails: String?,
        durationMs: Long,
        isSuccess: Boolean,
        isEngineCall: Boolean = true
    ) {
        val path = url.substringAfter("://").substringAfter("/", "/")
        val record = ApiCallRecord(
            id = "eng_${System.currentTimeMillis()}_${(1000..9999).random()}",
            timestampMs = System.currentTimeMillis() - durationMs,
            type = ApiCallType.ENGINE_API,
            name = "$method $path",
            method = method,
            urlOrCommand = url,
            requestHeaders = sanitizeHeaders(requestHeaders),
            requestBody = requestBody,
            requestContentType = "application/json",
            responseStatusCode = responseStatusCode,
            responseHeaders = responseHeaders,
            responseBody = responseBody,
            errorDetails = errorDetails,
            durationMs = durationMs,
            status = if (isSuccess) ApiCallStatus.SUCCESS else ApiCallStatus.ERROR,
            requestSizeBytes = requestBody?.encodeToByteArray()?.size?.toLong() ?: 0L,
            responseSizeBytes = responseBody?.encodeToByteArray()?.size?.toLong() ?: 0L,
            isEngineCall = isEngineCall
        )
        addRecord(record)
    }

    fun trackEngineCliCall(
        command: String,
        promptStdin: String?,
        outputStdout: String?,
        exitCode: Int,
        errorDetails: String?,
        durationMs: Long,
        isSuccess: Boolean,
        tokensIn: Int? = null,
        tokensOut: Int? = null
    ) {
        val bin = command.substringBefore(' ')
        val record = ApiCallRecord(
            id = "eng_cli_${System.currentTimeMillis()}_${(1000..9999).random()}",
            timestampMs = System.currentTimeMillis() - durationMs,
            type = ApiCallType.CLI_RUNNER,
            name = "Engine CLI: $bin",
            method = "CLI",
            urlOrCommand = command,
            requestBody = promptStdin,
            requestContentType = "text/plain",
            responseStatusCode = exitCode,
            responseBody = outputStdout,
            errorDetails = errorDetails,
            durationMs = durationMs,
            status = if (isSuccess) ApiCallStatus.SUCCESS else ApiCallStatus.ERROR,
            tokensIn = tokensIn,
            tokensOut = tokensOut,
            requestSizeBytes = promptStdin?.encodeToByteArray()?.size?.toLong() ?: 0L,
            responseSizeBytes = outputStdout?.encodeToByteArray()?.size?.toLong() ?: 0L,
            isEngineCall = true
        )
        addRecord(record)
    }

    fun trackAgentApiCall(
        provider: Provider,
        model: String,
        method: String,
        url: String,
        requestHeaders: Map<String, String>,
        requestBody: String?,
        responseStatusCode: Int?,
        responseHeaders: Map<String, String> = emptyMap(),
        responseBody: String?,
        errorDetails: String?,
        durationMs: Long,
        isSuccess: Boolean,
        tokensIn: Int? = null,
        tokensOut: Int? = null,
        tokensCached: Int? = null,
        isEngineCall: Boolean = false
    ) {
        // Calculate estimated cost
        val cost = estimateCostUsd(provider, model, tokensIn ?: 0, tokensOut ?: 0, tokensCached ?: 0)
        val record = ApiCallRecord(
            id = "agent_${System.currentTimeMillis()}_${(1000..9999).random()}",
            timestampMs = System.currentTimeMillis() - durationMs,
            type = ApiCallType.AGENT_API,
            name = "${provider.name.lowercase().replaceFirstChar { it.uppercase() }} ($model)",
            method = method,
            urlOrCommand = url,
            provider = provider,
            model = model,
            requestHeaders = sanitizeHeaders(requestHeaders),
            requestBody = requestBody,
            requestContentType = "application/json",
            responseStatusCode = responseStatusCode,
            responseHeaders = responseHeaders,
            responseBody = responseBody,
            errorDetails = errorDetails,
            durationMs = durationMs,
            status = if (isSuccess) ApiCallStatus.SUCCESS else ApiCallStatus.ERROR,
            tokensIn = tokensIn,
            tokensOut = tokensOut,
            tokensCached = tokensCached,
            estimatedCostUsd = cost,
            requestSizeBytes = requestBody?.encodeToByteArray()?.size?.toLong() ?: 0L,
            responseSizeBytes = responseBody?.encodeToByteArray()?.size?.toLong() ?: 0L,
            isEngineCall = isEngineCall
        )
        addRecord(record)
    }

    fun trackCliCall(
        provider: Provider?,
        model: String?,
        command: String,
        promptStdin: String?,
        outputStdout: String?,
        exitCode: Int,
        errorDetails: String?,
        durationMs: Long,
        isSuccess: Boolean,
        tokensIn: Int? = null,
        tokensOut: Int? = null,
        isEngineCall: Boolean = false
    ) {
        val bin = command.substringBefore(' ')
        val record = ApiCallRecord(
            id = "cli_${System.currentTimeMillis()}_${(1000..9999).random()}",
            timestampMs = System.currentTimeMillis() - durationMs,
            type = ApiCallType.CLI_RUNNER,
            name = if (isEngineCall) "Engine CLI: $bin" else "CLI: $bin",
            method = "CLI",
            urlOrCommand = command,
            provider = provider,
            model = model,
            requestBody = promptStdin,
            requestContentType = "text/plain",
            responseStatusCode = exitCode,
            responseBody = outputStdout,
            errorDetails = errorDetails,
            durationMs = durationMs,
            status = if (isSuccess) ApiCallStatus.SUCCESS else ApiCallStatus.ERROR,
            tokensIn = tokensIn,
            tokensOut = tokensOut,
            requestSizeBytes = promptStdin?.encodeToByteArray()?.size?.toLong() ?: 0L,
            responseSizeBytes = outputStdout?.encodeToByteArray()?.size?.toLong() ?: 0L,
            isEngineCall = isEngineCall
        )
        addRecord(record)
    }

    fun clear() {
        _records.value = emptyList()
    }

    private fun estimateCostUsd(provider: Provider, model: String, tokensIn: Int, tokensOut: Int, tokensCached: Int): Double? {
        val m = model.lowercase()
        return when {
            m.contains("claude-3-7-sonnet") || m.contains("claude-3-5-sonnet") -> {
                (tokensIn * 3.0 / 1_000_000.0) + (tokensOut * 15.0 / 1_000_000.0) + (tokensCached * 0.30 / 1_000_000.0)
            }
            m.contains("claude-3-5-haiku") -> {
                (tokensIn * 0.80 / 1_000_000.0) + (tokensOut * 4.0 / 1_000_000.0) + (tokensCached * 0.08 / 1_000_000.0)
            }
            m.contains("gpt-4o-mini") -> {
                (tokensIn * 0.15 / 1_000_000.0) + (tokensOut * 0.60 / 1_000_000.0)
            }
            m.contains("gpt-4o") -> {
                (tokensIn * 2.50 / 1_000_000.0) + (tokensOut * 10.0 / 1_000_000.0)
            }
            m.contains("deepseek-chat") || m.contains("deepseek-reasoner") -> {
                (tokensIn * 0.14 / 1_000_000.0) + (tokensOut * 0.28 / 1_000_000.0)
            }
            m.contains("gemini-2.0-flash") || m.contains("gemini-1.5-flash") -> {
                (tokensIn * 0.10 / 1_000_000.0) + (tokensOut * 0.40 / 1_000_000.0)
            }
            m.contains("gemini-1.5-pro") || m.contains("gemini-2.5-pro") -> {
                (tokensIn * 1.25 / 1_000_000.0) + (tokensOut * 5.00 / 1_000_000.0)
            }
            else -> {
                // Generic rough estimate
                (tokensIn * 1.0 / 1_000_000.0) + (tokensOut * 3.0 / 1_000_000.0)
            }
        }
    }
}
