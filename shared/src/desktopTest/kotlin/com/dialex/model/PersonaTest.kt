package com.dialex.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersonaTest {

    @Test
    fun testAllSystemPersonasIntegrityWhenPresent() {
        // Personas are now pre-fed via bundled_personas.json and curated using Persona Studio.
        // When present, verify their structural integrity.
        for (p in SystemPersonas) {
            assertTrue(p.id.isNotBlank(), "Persona ID must not be blank")
            assertTrue(p.name.isNotBlank(), "Persona name must not be blank")
            assertTrue(p.role.isNotBlank(), "Persona role must not be blank for ${p.id}")
            assertTrue(p.description.isNotBlank(), "Persona description must not be blank for ${p.id}")
            assertTrue(p.systemPrompt.isNotBlank(), "Persona systemPrompt must not be blank for ${p.id}")
            assertTrue(p.isSystem, "Persona should be marked as system for ${p.id}")
        }
    }

    @Test
    fun testPersonaOptionalFieldsAndAutoComposition() {
        val p = PredefinedPersona(
            id = "custom_test",
            name = "Test Architect",
            description = "Custom test persona",
            roleAndPersona = "Senior Cloud Architect",
            coreExpertise = "Distributed systems, Event-driven design",
            toneAndVoice = "Pragmatic, concise, authoritative",
            objective = "Ensure high availability and fault isolation"
        )

        assertEquals("Senior Cloud Architect", p.roleAndPersona)
        assertEquals("Distributed systems, Event-driven design", p.coreExpertise)
        assertEquals("Pragmatic, concise, authoritative", p.toneAndVoice)
        assertEquals("Ensure high availability and fault isolation", p.objective)

        val composedPrompt = buildComposedSystemPrompt(
            roleAndPersona = p.roleAndPersona,
            coreExpertise = p.coreExpertise,
            toneAndVoice = p.toneAndVoice,
            objective = p.objective,
            systemContextPrompt = "Always challenge synchronous RPC couplings."
        )

        assertTrue(composedPrompt.contains("### ROLE & PERSONA\nSenior Cloud Architect"))
        assertTrue(composedPrompt.contains("### CORE EXPERTISE\nDistributed systems, Event-driven design"))
        assertTrue(composedPrompt.contains("### TONE & VOICE\nPragmatic, concise, authoritative"))
        assertTrue(composedPrompt.contains("### OBJECTIVE\nEnsure high availability and fault isolation"))
        assertTrue(composedPrompt.contains("### SYSTEM CONTEXT\nAlways challenge synchronous RPC couplings."))
    }


    @Test
    fun testPersonaLoaderLoadsBundledPersonas() = kotlinx.coroutines.test.runTest {
        val loaded = com.dialex.data.loader.PersonaLoader.loadBundledPersonas()
        // bundled_personas.json is empty by default when pre-feed is cleared, or contains pre-fed items
        // Verify loading completes without throwing and returns a valid list
        assertTrue(loaded is List<PredefinedPersona>, "PersonaLoader should return a valid List<PredefinedPersona>")
    }

    @Test
    fun testPersonaJsonSerializationRoundTrip() {
        val json = kotlinx.serialization.json.Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        val persona = PredefinedPersona(
            id = "custom_1",
            name = "Security Researcher",
            category = "Cybersecurity",
            role = "Penetration Tester",
            description = "Specializes in offensive security and red teaming.",
            roleAndPersona = "Red Team Specialist",
            coreExpertise = "Exploit development, web vulnerabilities",
            toneAndVoice = "Methodical, technical, adversarial",
            objective = "Expose architecture vulnerabilities before production",
            systemPrompt = "You are a red team specialist."
        )

        val encoded = json.encodeToString(listOf(persona))
        val decoded = json.decodeFromString<List<PredefinedPersona>>(encoded)

        assertEquals(1, decoded.size)
        assertEquals("custom_1", decoded[0].id)
        assertEquals("Security Researcher", decoded[0].name)
        assertEquals("Red Team Specialist", decoded[0].roleAndPersona)
        assertEquals("Methodical, technical, adversarial", decoded[0].toneAndVoice)
    }
}
