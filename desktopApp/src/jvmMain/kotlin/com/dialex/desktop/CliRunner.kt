package com.dialex.desktop

import com.dialex.export.exportFileName
import com.dialex.export.toMarkdown
import com.dialex.model.Agent
import com.dialex.model.ApiKeys
import com.dialex.model.CliCommands
import com.dialex.model.DebateConfig
import com.dialex.model.Discussion
import com.dialex.model.DiscussionStatus
import com.dialex.model.Project
import com.dialex.model.Provider
import com.dialex.model.RoundMode
import com.dialex.model.RunMode
import com.dialex.model.defaultModel
import com.dialex.model.label
import com.dialex.orchestrator.DebateOrchestrator
import com.dialex.runner.ApiAgentRunner
import com.dialex.runner.CliAgentRunner
import com.dialex.store.AppStore
import java.io.File
import kotlin.random.Random

/**
 * Non-interactive entry point — runs exactly one debate to completion (or until paused/
 * error) and exits, no window, no event loop. Meant for a self-hosted server, a cron job, or
 * a CI step: `java -jar roundtable.jar --topic "..." --agent anthropic:claude-sonnet-5 ...`.
 *
 * Reuses the same Settings file as the GUI app by default (`~/.aidebate/state.json`) so a
 * machine that's ever run the windowed app once already has working API keys/CLI commands —
 * but every value can be overridden per-invocation via env vars or flags, so a bare server
 * that's never run the GUI works too.
 */
suspend fun runHeadless(args: Array<String>): Int {
    val opts = try {
        parseArgs(args)
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        printUsage()
        return 1
    }
    if (opts.help) {
        printUsage()
        return 0
    }
    if (opts.topic.isBlank()) {
        System.err.println("Missing required --topic")
        printUsage()
        return 1
    }

    val stateFile = File(
        opts.statePath ?: run {
            val dialexFile = File(System.getProperty("user.home"), ".dialex/state.json")
            if (dialexFile.exists()) dialexFile.absolutePath else "${System.getProperty("user.home")}/.aidebate/state.json"
        }
    )
    val saved = if (stateFile.exists()) {
        runCatching { AppStore(stateFile).load() }.getOrNull()
    } else null

    // Env var (or flag, for CI secrets managers that don't do env well) wins over whatever's
    // saved in Settings — a server config should be able to fully override without touching
    // that file at all.
    val apiKeys = ApiKeys(
        anthropic = opts.keys[Provider.ANTHROPIC] ?: System.getenv("ANTHROPIC_API_KEY") ?: saved?.apiKeys?.anthropic ?: "",
        openai = opts.keys[Provider.OPENAI] ?: System.getenv("OPENAI_API_KEY") ?: saved?.apiKeys?.openai ?: "",
        gemini = opts.keys[Provider.GEMINI] ?: System.getenv("GEMINI_API_KEY") ?: System.getenv("GOOGLE_API_KEY") ?: saved?.apiKeys?.gemini ?: "",
        grok = opts.keys[Provider.GROK] ?: System.getenv("XAI_API_KEY") ?: System.getenv("GROK_API_KEY") ?: saved?.apiKeys?.grok ?: "",
        deepseek = opts.keys[Provider.DEEPSEEK] ?: System.getenv("DEEPSEEK_API_KEY") ?: saved?.apiKeys?.deepseek ?: "",
        mistral = opts.keys[Provider.MISTRAL] ?: System.getenv("MISTRAL_API_KEY") ?: saved?.apiKeys?.mistral ?: "",
    )
    val cliCommands = saved?.cliCommands ?: CliCommands()

    if (opts.agents.isEmpty()) {
        System.err.println("At least one --agent is required (the first one is the Primary Agent).")
        return 1
    }
    val agents = if (opts.caveman) {
        opts.agents.map { it.copy(caveman = true) }
    } else {
        opts.agents
    }
    val config = DebateConfig(
        topic = opts.topic,
        commonContext = opts.context,
        commonInfo = opts.info,
        primary = agents[0],
        secondary = agents.getOrNull(1),
        tertiary = agents.getOrNull(2),
        quaternary = agents.getOrNull(3),
        quinary = agents.getOrNull(4),
        senary = agents.getOrNull(5),
        roundMode = if (opts.unlimited) RoundMode.UNLIMITED else RoundMode.FIXED,
        maxRounds = opts.rounds,
    )

    val runnerFor = { agent: Agent ->
        if (agent.runMode == RunMode.CLI) CliAgentRunner(cliCommands) else ApiAgentRunner(apiKeys.asMap())
    }
    val orchestrator = DebateOrchestrator(runnerFor)

    if (!opts.quiet) {
        println("Topic: ${config.topic}")
        println("Participants: ${config.agents.joinToString(", ") { it.label() }}")
        println("---")
    }

    val result = orchestrator.run(
        config = config,
        tokenBudget = opts.tokenBudget,
    ) { msg ->
        if (opts.quiet) return@run
        val label = config.agents.first { it.provider == msg.agentId }.label()
        println()
        println(if (msg.isError) "[round ${msg.round}] $label — ERROR" else "[round ${msg.round}] $label")
        println(msg.content)
    }

    println()
    when {
        result.error != null -> System.err.println("Debate stopped with an error: ${result.error}")
        result.paused -> println("Debate paused (unlimited mode, no natural end reached in this invocation).")
        result.conclusion != null -> {
            println("=== Conclusion (${config.primary.label()}) ===")
            println(result.conclusion)
        }
    }

    val discussion = Discussion(
        id = Random.nextLong().toString(36),
        projectId = opts.project ?: "cli",
        name = opts.name ?: opts.topic.take(60),
        config = config,
        status = when {
            result.error != null -> DiscussionStatus.ERROR
            result.paused -> DiscussionStatus.PAUSED
            else -> DiscussionStatus.DONE
        },
        transcript = result.transcript,
        conclusion = result.conclusion,
    )

    opts.output?.let { path ->
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(discussion.toMarkdown())
        if (!opts.quiet) println("Exported: ${file.absolutePath}")
    }

    val projectName = opts.project
    if (projectName != null) {
        val store = AppStore(stateFile)
        val state = store.load()
        val project = state.projects.firstOrNull { it.name == projectName }
            ?: Project(id = discussion.projectId, name = projectName)
        store.save(
            state.copy(
                projects = if (state.projects.any { it.id == project.id }) state.projects else state.projects + project,
                discussions = state.discussions + discussion.copy(projectId = project.id),
            ),
        )
        if (!opts.quiet) println("Saved to project \"$projectName\" in ${stateFile.absolutePath}")
    }

    return if (result.error != null) 1 else 0
}

private class HeadlessOptions {
    var help = false
    var topic = ""
    var context = ""
    var info = ""
    val agents = mutableListOf<Agent>()
    var rounds = 3
    var unlimited = false
    var caveman = false
    var quiet = false
    var output: String? = null
    var project: String? = null
    var name: String? = null
    var statePath: String? = null
    var tokenBudget = 1_000_000
    val keys = mutableMapOf<Provider, String>()
}

private fun parseArgs(args: Array<String>): HeadlessOptions {
    val opts = HeadlessOptions()
    var i = 0
    fun next(flag: String): String {
        i++
        require(i < args.size) { "Missing value for $flag" }
        return args[i]
    }
    while (i < args.size) {
        when (val arg = args[i]) {
            "--help", "-h" -> opts.help = true
            "--topic" -> opts.topic = next(arg)
            "--context" -> opts.context = next(arg)
            "--info" -> opts.info = next(arg)
            "--agent" -> opts.agents += parseAgent(next(arg))
            "--rounds" -> opts.rounds = next(arg).toIntOrNull() ?: throw IllegalArgumentException("--rounds must be a number")
            "--unlimited" -> opts.unlimited = true
            "--caveman" -> opts.caveman = true
            "--quiet" -> opts.quiet = true
            "--output" -> opts.output = next(arg)
            "--project" -> opts.project = next(arg)
            "--name" -> opts.name = next(arg)
            "--state" -> opts.statePath = next(arg)
            "--token-budget" -> opts.tokenBudget = next(arg).toIntOrNull() ?: throw IllegalArgumentException("--token-budget must be a number")
            "--anthropic-key" -> opts.keys[Provider.ANTHROPIC] = next(arg)
            "--openai-key" -> opts.keys[Provider.OPENAI] = next(arg)
            "--gemini-key" -> opts.keys[Provider.GEMINI] = next(arg)
            "--grok-key" -> opts.keys[Provider.GROK] = next(arg)
            "--deepseek-key" -> opts.keys[Provider.DEEPSEEK] = next(arg)
            "--mistral-key" -> opts.keys[Provider.MISTRAL] = next(arg)
            else -> throw IllegalArgumentException("Unknown argument: $arg")
        }
        i++
    }
    return opts
}

/** `provider[:model[:api|cli]]`, e.g. `anthropic:claude-sonnet-5:api` or just `gemini` for
 * an all-defaults agent. `custom:<display name>[:model]` for a CUSTOM CLI tool. */
private fun parseAgent(spec: String): Agent {
    val parts = spec.split(":")
    val provider = parseProvider(parts.getOrElse(0) { "" })
        ?: throw IllegalArgumentException("Unknown provider \"${parts.getOrNull(0)}\" in --agent $spec")
    if (provider == Provider.CUSTOM) {
        val displayName = parts.getOrNull(1)
            ?: throw IllegalArgumentException("--agent custom:<display name> needs a display name, e.g. custom:Aider")
        return Agent(provider = provider, model = parts.getOrElse(2) { "" }, runMode = RunMode.CLI, displayName = displayName)
    }
    val model = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: provider.defaultModel()
    val mode = when (parts.getOrNull(2)?.lowercase()) {
        "cli" -> RunMode.CLI
        else -> RunMode.API // server-friendly default — no interactive CLI login to worry about
    }
    return Agent(provider = provider, model = model, runMode = mode)
}

private fun parseProvider(name: String): Provider? = when (name.lowercase()) {
    "anthropic", "claude" -> Provider.ANTHROPIC
    "openai", "chatgpt", "gpt" -> Provider.OPENAI
    "gemini", "google" -> Provider.GEMINI
    "grok", "xai" -> Provider.GROK
    "deepseek" -> Provider.DEEPSEEK
    "mistral" -> Provider.MISTRAL
    "custom" -> Provider.CUSTOM
    else -> null
}

private fun printUsage() {
    println(
        """
        Usage: roundtable --topic "..." --agent <provider[:model[:api|cli]]> [--agent ...] [options]

        Runs one debate headlessly (no window) and exits — for servers, cron, CI.

        Required:
          --topic "text"           What the agents debate.
          --agent spec             1-5 times. First is the Primary Agent (speaks first, gives
                                    the final decision). Providers: anthropic, openai, gemini,
                                    grok, deepseek, mistral, custom:<name>.
                                    e.g. --agent anthropic:claude-sonnet-5:api

        Optional:
          --context "text"         Shared background every agent sees.
          --info "text"            Extra shared information/rules.
          --rounds N                (default 3, ignored if --unlimited)
          --unlimited               Run until a natural stop (agreement) or --rounds isn't hit;
                                     no forced cutoff. Careful with cost.
          --token-budget N          Hard stop once API-mode usage crosses N tokens (default
                                     1000000, 0 disables it).
          --caveman                 Ultra-terse responses from every agent.
          --quiet                   Only print the final result, not each turn live.
          --output path.md          Write the formatted transcript there.
          --project "name"          Save this run as a discussion under this project in the
                                     same state.json the GUI app uses (creates it if new).
          --name "text"             Discussion name when --project is set (default: topic).
          --state path              Override the state.json used for Settings/--project
                                     (default ~/.aidebate/state.json).
          --<provider>-key value    Override that provider's API key for this run only
                                     (anthropic, openai, gemini, grok, deepseek, mistral).
                                     Falls back to the matching env var, then Settings.

        Env vars (fallback, in order after an explicit --<provider>-key):
          ANTHROPIC_API_KEY, OPENAI_API_KEY, GEMINI_API_KEY (or GOOGLE_API_KEY),
          XAI_API_KEY (or GROK_API_KEY), DEEPSEEK_API_KEY, MISTRAL_API_KEY

        With no arguments at all, launches the normal windowed app instead.
        """.trimIndent(),
    )
}
