package com.dialex.service

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readUTF8Line
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

@Serializable
data class OllamaModelInfo(
    val name: String,
    val modifiedAt: String = "",
    val sizeBytes: Long = 0,
    val parameterSize: String = "",
    val quantizationLevel: String = "",
    val family: String = "",
) {
    fun formattedSize(): String {
        if (sizeBytes <= 0) return ""
        val gb = sizeBytes.toDouble() / (1024 * 1024 * 1024)
        return if (gb >= 1.0) {
            val rounded = (gb * 10).toInt() / 10.0
            "$rounded GB"
        } else {
            val mb = (sizeBytes / (1024 * 1024)).toInt()
            "$mb MB"
        }
    }

    fun tagSummary(): String = buildString {
        append(name)
        val details = mutableListOf<String>()
        if (parameterSize.isNotBlank()) details.add(parameterSize)
        if (quantizationLevel.isNotBlank()) details.add(quantizationLevel)
        val size = formattedSize()
        if (size.isNotBlank()) details.add(size)
        if (details.isNotEmpty()) {
            append(" (${details.joinToString(" · ")})")
        }
    }
}

sealed interface OllamaHealthStatus {
    data object Checking : OllamaHealthStatus
    data class Online(val version: String, val modelCount: Int) : OllamaHealthStatus
    data class Offline(val message: String) : OllamaHealthStatus
}

/**
 * Service for local Ollama instance interaction:
 * - Health check & version detection
 * - Model auto-discovery (`GET /api/tags`)
 * - Streaming one-click model downloads (`POST /api/pull`)
 */
class OllamaService(
    private val client: HttpClient = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            requestTimeoutMillis = 600_000 // 10 minutes for model downloads
            connectTimeoutMillis = 5_000
        }
    }
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun normalizeUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
    }

    suspend fun checkHealth(endpointUrl: String): OllamaHealthStatus {
        val url = normalizeUrl(endpointUrl)
        return try {
            val versionResp: HttpResponse = client.get("$url/api/version")
            if (versionResp.status.isSuccess()) {
                val bodyStr = versionResp.bodyAsText()
                val parsed = json.decodeFromString<JsonObject>(bodyStr)
                val version = parsed["version"]?.jsonPrimitive?.content ?: "unknown"
                val models = fetchInstalledModels(url)
                OllamaHealthStatus.Online(version = version, modelCount = models.size)
            } else {
                OllamaHealthStatus.Offline("HTTP ${versionResp.status.value}")
            }
        } catch (e: Exception) {
            OllamaHealthStatus.Offline(e.message ?: "Connection failed")
        }
    }

    suspend fun fetchInstalledModels(endpointUrl: String): List<OllamaModelInfo> {
        val url = normalizeUrl(endpointUrl)
        return try {
            val resp: HttpResponse = client.get("$url/api/tags")
            if (!resp.status.isSuccess()) return emptyList()
            val bodyStr = resp.bodyAsText()
            val parsed = json.decodeFromString<JsonObject>(bodyStr)
            val modelsArray = parsed["models"]?.jsonArray ?: return emptyList()

            modelsArray.mapNotNull { element ->
                val obj = element.jsonObject
                val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val modifiedAt = obj["modified_at"]?.jsonPrimitive?.content ?: ""
                val sizeBytes = obj["size"]?.jsonPrimitive?.longOrNull ?: 0L
                val details = obj["details"]?.jsonObject
                val paramSize = details?.get("parameter_size")?.jsonPrimitive?.content ?: ""
                val quantLevel = details?.get("quantization_level")?.jsonPrimitive?.content ?: ""
                val family = details?.get("family")?.jsonPrimitive?.content ?: ""

                OllamaModelInfo(
                    name = name,
                    modifiedAt = modifiedAt,
                    sizeBytes = sizeBytes,
                    parameterSize = paramSize,
                    quantizationLevel = quantLevel,
                    family = family
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun pullModel(
        endpointUrl: String,
        modelName: String,
        onProgress: (status: String, completedBytes: Long, totalBytes: Long) -> Unit
    ): Result<Unit> {
        val url = normalizeUrl(endpointUrl)
        return runCatching {
            val resp: HttpResponse = client.post("$url/api/pull") {
                contentType(ContentType.Application.Json)
                setBody("""{"name": "${modelName.trim()}"}""")
            }
            if (!resp.status.isSuccess()) {
                error("Failed to pull model: HTTP ${resp.status.value}")
            }

            val channel = resp.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                if (line.isBlank()) continue
                try {
                    val jsonObj = json.decodeFromString<JsonObject>(line)
                    val status = jsonObj["status"]?.jsonPrimitive?.content ?: ""
                    val completed = jsonObj["completed"]?.jsonPrimitive?.longOrNull ?: 0L
                    val total = jsonObj["total"]?.jsonPrimitive?.longOrNull ?: 0L
                    onProgress(status, completed, total)
                } catch (_: Exception) {
                    // Skip malformed JSON lines
                }
            }
        }
    }
}
