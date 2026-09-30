package com.dialex.data.loader

import com.dialex.model.PredefinedPersona
import dialex.shared.generated.resources.Res
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

object PersonaLoader {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private var cachedPersonas: List<PredefinedPersona>? = null

    suspend fun loadBundledPersonas(): List<PredefinedPersona> {
        cachedPersonas?.let { return it }
        return try {
            val bytes = Res.readBytes("files/personas/bundled_personas.json")
            val parsed = json.decodeFromString<List<PredefinedPersona>>(bytes.decodeToString())
            cachedPersonas = parsed
            parsed
        } catch (e: Exception) {
            cachedPersonas ?: emptyList()
        }
    }

    fun getCachedBundledPersonas(): List<PredefinedPersona> {
        cachedPersonas?.let { return it }
        return try {
            runBlocking {
                loadBundledPersonas()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setCachedBundledPersonas(personas: List<PredefinedPersona>) {
        cachedPersonas = personas
    }
}
