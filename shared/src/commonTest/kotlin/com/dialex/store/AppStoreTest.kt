package com.dialex.store

import com.dialex.model.ApiKeys
import com.dialex.model.AppState
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Covers the three P0 data-safety properties AppStore is responsible for: a saved API key
 * never sits in state.json as plaintext, a save leaves a recoverable previous version
 * behind, and none of that breaks a normal save/load round trip. */
class AppStoreTest {
    private val dir = File.createTempFile("roundtable-test", "").let { it.delete(); it.mkdirs(); it }
    private val stateFile = File(dir, "state.json")
    private val store = AppStore(stateFile)

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun api_keys_are_never_written_to_disk_as_plaintext() {
        val secret = "sk-ant-super-secret-key"
        store.save(AppState(apiKeys = ApiKeys(anthropic = secret)))

        assertFalse(secret in stateFile.readText(), "plaintext key must not appear in state.json")
        assertEquals(secret, store.load().apiKeys.anthropic)
    }

    @Test
    fun a_legacy_plaintext_key_still_loads_and_gets_encrypted_on_next_save() {
        // Simulates a state.json written before encryption existed — no key file, key
        // strings stored raw.
        stateFile.writeText("""{"apiKeys":{"anthropic":"sk-ant-legacy","openai":"","gemini":""}}""")

        val loaded = store.load()
        assertEquals("sk-ant-legacy", loaded.apiKeys.anthropic)

        store.save(loaded)
        assertFalse("sk-ant-legacy" in stateFile.readText())
        assertEquals("sk-ant-legacy", store.load().apiKeys.anthropic)
    }

    @Test
    fun each_save_leaves_a_recoverable_backup_of_the_previous_version() {
        store.save(AppState(compactionModel = "first"))
        store.save(AppState(compactionModel = "second"))

        val backup = File(dir, "state.json.bak1")
        assertTrue(backup.exists())
        assertEquals("first", AppStore(backup).load().compactionModel)
        assertEquals("second", store.load().compactionModel)
    }

    @Test
    fun full_state_round_trips_through_save_and_load() {
        val original = AppState(
            discussions = emptyList(),
            apiKeys = ApiKeys(anthropic = "a", openai = "b", gemini = "c"),
            compactionModel = "claude-haiku-4-5-20251001",
            tokenBudget = 12_345,
        )
        store.save(original)
        assertEquals(original, store.load())
    }
}
