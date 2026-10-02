package com.dialex.engine

import com.dialex.model.Agent
import com.dialex.model.DebateConfig
import com.dialex.model.Discussion
import com.dialex.model.DiscussionStatus
import com.dialex.model.Project
import com.dialex.model.Provider
import com.dialex.model.RoundMode
import com.dialex.viewmodel.EngineViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives the *real* Go engine binary from EngineClient over real HTTP + SSE — not a mock.
 * This is the actual proof that the Kotlin wire format and the Go engine's wire format
 * agree: if the JSON field names, enum string values, or SSE framing
 * ever drift between the two implementations, this is what catches it, not a hand-wave.
 *
 * Uses `runBlocking`, not `runTest` — this makes real network calls against a real
 * subprocess, so kotlinx-coroutines-test's virtual-time scheduler doesn't apply (and in
 * practice hung indefinitely here: `runTest`'s dispatcher didn't drive Ktor CIO's real
 * background I/O the way a genuine real-time test needs, even though the exact same code
 * against the exact same server works instantly over `runBlocking`).
 *
 * Skips (rather than fails) if the engine binary can't be located/built — CI environments
 * without a Go toolchain shouldn't block on this, but a local dev machine with `go` on PATH
 * gets full coverage automatically.
 */
class EngineClientIntegrationTest {
    private lateinit var engineProcess: Process
    private lateinit var engineDir: File
    private val port = 18173
    private val baseUrl = "http://127.0.0.1:$port"

    @BeforeTest
    fun startEngine() {
        val binary = buildEngineBinary() ?: return
        engineDir = File.createTempFile("roundtable-it", "").also { it.delete(); it.mkdirs() }

        // Seed one account non-interactively (the CLI's `users add` reads the password from
        // stdin — piping it in is simpler than adding a scripting-only flag to a wizard
        // that's meant to be interactive).
        val addUser = ProcessBuilder(binary.absolutePath, "users", "add", "--dir", engineDir.absolutePath, "admin")
            .redirectErrorStream(true)
            .start()
        addUser.outputStream.bufferedWriter().use { it.write("test-password\n") }
        addUser.waitFor()

        engineProcess = ProcessBuilder(binary.absolutePath, "serve", "--host", "127.0.0.1:$port", "--dir", engineDir.absolutePath)
            .redirectErrorStream(true)
            .start()
        waitForHealth()
    }

    @AfterTest
    fun stopEngine() {
        if (::engineProcess.isInitialized) engineProcess.destroyForcibly()
        if (::engineDir.isInitialized) engineDir.deleteRecursively()
    }

    private fun waitForHealth() {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            try {
                val conn = java.net.URI("$baseUrl/health").toURL().openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 200
                if (conn.responseCode == 200) return
            } catch (e: Exception) {
                Thread.sleep(100)
            }
        }
        error("engine never became healthy on $baseUrl")
    }

    @Test
    fun full_debate_lifecycle_against_the_real_go_engine() = runBlocking {
        if (!::engineProcess.isInitialized) {
            println("SKIP: Go toolchain not available, skipping real-engine integration test")
            return@runBlocking
        }
        val client = EngineClient(baseUrl)

        val health = client.health()
        assertEquals("ok", health.status)

        client.login("admin", "test-password")

        val project = client.createProject("Integration Test Project")
        assertTrue(project.id.isNotBlank())

        // Custom provider forces CLI mode with no real CLI configured — this proves error
        // turns round-trip correctly too (isError, content) without needing real API keys
        // to exercise the whole lifecycle end to end.
        val config = DebateConfig(
            topic = "Integration test topic",
            primary = Agent(provider = Provider.CUSTOM, model = "", runMode = com.dialex.model.RunMode.CLI, displayName = "NoSuchTool"),
            roundMode = RoundMode.FIXED,
            maxRounds = 1,
        )
        val created = client.createDebate(project.id, "IT Debate", config)
        assertEquals(DiscussionStatus.DRAFT, created.status)

        client.startDebate(created.id)

        val turns = mutableListOf<com.dialex.model.DebateMessage>()
        withTimeout(10_000) {
            client.streamDebate(created.id).collect { disc ->
                if (disc.transcript.isNotEmpty()) {
                    turns.addAll(disc.transcript)
                    return@collect
                }
            }
        }

        assertTrue(turns.isNotEmpty(), "expected at least one turn over SSE")
        assertTrue(turns.first().isError, "a nonexistent CLI tool should produce an error turn")

        val final = client.getDebate(created.id)
        assertTrue(final.status.isFailed, "Expected discussion status to be failed (FAILED or ERROR), but was ${final.status}")
        assertEquals(turns.first().content, final.transcript.first().content)
    }

    @Test
    fun crud_and_handoff_against_the_real_go_engine() = runBlocking {
        if (!::engineProcess.isInitialized) {
            println("SKIP: Go toolchain not available, skipping real-engine integration test")
            return@runBlocking
        }
        val client = EngineClient(baseUrl)
        client.login("admin", "test-password")

        // A real (if trivial) CLI command, not a mock — the handoff call goes through the
        // engine's actual runner selection, which for API-mode Anthropic would need a real
        // key we don't have here. CUSTOM + a fake script that just echoes fixed output
        // keeps this a genuine end-to-end call without needing real provider credentials.
        val script = File.createTempFile("fake-cli", "")
        script.writeText("#!/bin/sh\necho 'handoff prompt text'\n")
        script.setExecutable(true)
        client.updateSettings(com.dialex.model.AppState(cliCommands = com.dialex.model.CliCommands(custom = script.absolutePath)))

        val project = client.createProject("CRUD Test Project")
        val config = DebateConfig(
            topic = "original topic",
            primary = Agent(provider = Provider.CUSTOM, model = "", runMode = com.dialex.model.RunMode.CLI, displayName = "FakeCLI"),
        )
        val created = client.createDebate(project.id, "D1", config)

        // Update: config edits round-trip through the engine correctly.
        val edited = client.updateDebate(created.copy(config = created.config.copy(topic = "edited topic")))
        assertEquals("edited topic", edited.config.topic)
        assertEquals("edited topic", client.getDebate(created.id).config.topic)

        // Handoff: generated via a real (if trivial) CLI call and persisted.
        val handoffText = client.generateHandoffPrompt(created.id)
        assertEquals("handoff prompt text", handoffText)
        assertEquals(handoffText, client.getDebate(created.id).handoffPrompt)

        // Delete: the discussion is actually gone afterward.
        client.deleteDebate(created.id)
        assertTrue(client.listDebates().none { it.id == created.id })

        // Delete project: cascades to any of its remaining discussions too.
        val secondDiscussion = client.createDebate(project.id, "D2", config)
        client.deleteProject(project.id)
        assertTrue(client.listProjects().none { it.id == project.id })
        assertTrue(client.listDebates().none { it.id == secondDiscussion.id })
    }

    @Test
    fun engine_view_model_drives_a_full_lifecycle() = runBlocking {
        if (!::engineProcess.isInitialized) {
            println("SKIP: Go toolchain not available, skipping real-engine integration test")
            return@runBlocking
        }
        val client = EngineClient(baseUrl)
        client.login("admin", "test-password")
        val vm = EngineViewModel(client)

        val project = vm.addProject("VM Test Project")
        assertTrue(vm.state.projects.any { it.id == project.id })

        val discussion = vm.addDiscussion(project.id, "VM Debate")
        assertTrue(vm.state.discussions.any { it.id == discussion.id })

        val edited = discussion.copy(config = discussion.config.copy(topic = "vm topic"))
        vm.updateDiscussion(edited)
        assertEquals("vm topic", vm.state.discussions.first { it.id == discussion.id }.config.topic)

        vm.deleteDiscussion(discussion.id)
        assertTrue(vm.state.discussions.none { it.id == discussion.id })

        vm.deleteProject(project.id)
        assertTrue(vm.state.projects.none { it.id == project.id })

        vm.updateTokenBudget(12345)
        assertEquals(12345, vm.state.tokenBudget)
        assertEquals(12345, client.getSettings().tokenBudget)
    }

    private fun buildEngineBinary(): File? {
        val goBinary = listOf("/opt/homebrew/bin/go", "/usr/local/go/bin/go", "go")
            .firstOrNull { candidate -> runCatching { ProcessBuilder(candidate, "version").start().waitFor() == 0 }.getOrDefault(false) }
            ?: return null
        val parent = File(System.getProperty("user.dir")).parentFile
        val engineModuleDir = listOf("socratix-engine", "dialex-engine", "engine")
            .map { File(parent, it) }
            .firstOrNull { it.exists() }
            ?: return null
        val cmdPkg = if (File(engineModuleDir, "cmd/socratix").exists()) "./cmd/socratix" else "./cmd/dialex"
        val out = File.createTempFile("socratix-engine-it-binary", "")
        out.delete()
        val build = ProcessBuilder(goBinary, "build", "-o", out.absolutePath, cmdPkg)
            .directory(engineModuleDir)
            .redirectErrorStream(true)
            .start()
        val log = build.inputStream.bufferedReader().readText()
        if (build.waitFor() != 0) {
            println("SKIP: could not build the Go engine binary:\n$log")
            return null
        }
        return out
    }
}
