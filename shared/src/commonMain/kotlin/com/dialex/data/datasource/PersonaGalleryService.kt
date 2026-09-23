package com.dialex.data.datasource

import com.dialex.model.PersonaGalleryResponse
import com.dialex.model.PredefinedPersona
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object PersonaGalleryService {
    const val DEFAULT_GALLERY_URL = "https://raw.githubusercontent.com/dialex-ai/personas/main/gallery.json"
    const val DEFAULT_WEB_GALLERY_URL = "https://dialex.dev/gallery"

    private val httpClient by lazy {
        HttpClient {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    coerceInputValues = true
                })
            }
        }
    }

    private var cachedGallery: List<PredefinedPersona>? = null

    suspend fun fetchGalleryPersonas(url: String = DEFAULT_GALLERY_URL): List<PredefinedPersona> {
        return try {
            val response: PersonaGalleryResponse = httpClient.get(url).body()
            cachedGallery = response.personas
            response.personas
        } catch (e: Exception) {
            // If remote fails or offline, return cached or fallback sample
            cachedGallery ?: emptyList()
        }
    }

    fun getCachedGalleryPersonas(): List<PredefinedPersona> = cachedGallery ?: emptyList()
}
