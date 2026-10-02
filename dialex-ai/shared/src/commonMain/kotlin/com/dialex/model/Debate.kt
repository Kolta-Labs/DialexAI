package com.dialex.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Which provider backs an agent's model. Up to 6 seats can be filled at once (see
 * [DebateConfig]), one per provider — GROK/DEEPSEEK/MISTRAL round out the catalog beyond
 * the original three so 6 distinct real agents is actually reachable. CUSTOM is any other
 * CLI tool none of these cover (Aider, Cursor CLI, a local Ollama wrapper, ...) — CLI-only
 * (no known API shape to call), named by [Agent.displayName] since there's no fixed brand
 * for it. */
@Serializable
enum class Provider { ANTHROPIC, OPENAI, GEMINI, GROK, DEEPSEEK, MISTRAL, OLLAMA, CUSTOM }

/** Static brand name for the known providers; CUSTOM has no fixed brand — callers needing a
 * label for a specific seat should use [Agent.label] instead, which falls back to this only
 * when [Agent.displayName] is blank. */
fun Provider.brandName(): String = when (this) {
    Provider.ANTHROPIC -> "Claude"
    Provider.OPENAI -> "ChatGPT"
    Provider.GEMINI -> "Gemini"
    Provider.GROK -> "Grok"
    Provider.DEEPSEEK -> "DeepSeek"
    Provider.MISTRAL -> "Mistral"
    Provider.OLLAMA -> "Ollama (Local)"
    Provider.CUSTOM -> "Custom"
}

/** Default model per provider — deliberately the fast/balanced tier (Sonnet for Claude,
 * Flash for Gemini, and the closest equivalent for the rest) rather than each provider's
 * biggest flagship, since a debate can run many turns per agent and doesn't need
 * maximum-reasoning latency/cost on every single one. Blank for CUSTOM — freeform, no
 * curated model list to default from. */
fun Provider.defaultModel(): String = when (this) {
    Provider.ANTHROPIC -> "claude-sonnet-5"
    Provider.OPENAI -> "gpt-5.6-sol"
    Provider.GEMINI -> "gemini-3.7-flash"
    Provider.GROK -> "grok-4-fast"
    Provider.DEEPSEEK -> "deepseek-chat"
    Provider.MISTRAL -> "mistral-medium-latest"
    Provider.OLLAMA -> "llama3.2"
    Provider.CUSTOM -> ""
}

/** Recommended & popular models per provider for quick selection. */
fun Provider.knownModels(): List<String> = when (this) {
    Provider.ANTHROPIC -> listOf("claude-sonnet-5", "claude-3-7-sonnet", "claude-3-5-sonnet", "claude-3-5-haiku", "claude-3-opus")
    Provider.OPENAI -> listOf("gpt-5.6-sol", "gpt-4o", "gpt-4o-mini", "o3-mini", "o1")
    Provider.GEMINI -> listOf("gemini-3.7-flash", "gemini-2.0-flash", "gemini-2.0-pro-exp", "gemini-1.5-pro", "gemini-1.5-flash")
    Provider.GROK -> listOf("grok-4-fast", "grok-2-latest", "grok-beta")
    Provider.DEEPSEEK -> listOf("deepseek-chat", "deepseek-reasoner", "deepseek-coder")
    Provider.MISTRAL -> listOf("mistral-medium-latest", "mistral-large-latest", "codestral-latest")
    Provider.OLLAMA -> listOf("llama3.3", "llama3.2", "deepseek-r1:8b", "deepseek-r1:14b", "qwen2.5-coder:7b", "qwen2.5-coder:14b", "phi4", "mistral-nemo")
    Provider.CUSTOM -> emptyList()
}

/** How a provider call is made. API works on every platform; CLI is desktop-only, and is
 * the default — API keys and CLI commands both live in the global Settings screen, not
 * per-agent. Android agents are forced to API since there's no CLI to shell out to. */
@Serializable
enum class RunMode { API, CLI }

/** FIXED runs maxRounds then the primary agent gives the final decision. UNLIMITED runs until
 * paused — it never completes on its own. Either way Pause halts mid-debate and Resume
 * continues from the exact same point. */
@Serializable
enum class RoundMode { FIXED, UNLIMITED }

/**
 * One participant. `provider` is freely chosen per seat in [DebateConfig] — any seat can be
 * any [Provider], and several seats may share one (one API key, several personas). A turn is
 * identified by its seat ([DebateMessage.seatId]); [DebateMessage.agentId] is only the provider.
 */
@Serializable
data class Agent(
    val provider: Provider,
    /** Freeform for CUSTOM (no curated list); one of [Provider]'s known model names otherwise. */
    val model: String,
    val runMode: RunMode = RunMode.CLI,
    /** Overrides the global Settings CLI command for this provider, if set. Usually null —
     * the command is resolved from Settings at run time. */
    val cliCommand: String? = null,
    /** Individual starting prompt/persona for this agent only. */
    val systemPrompt: String = "",
    /** Individual background context for this agent only. */
    val context: String = "",
    /** Required for CUSTOM (no fixed brand to fall back on); ignored for the known
     * providers unless set, in which case it overrides their brand name too. */
    val displayName: String = "",
    /** The predefined-persona ID this agent was seeded from, or null if hand-configured.
     * Not used at runtime — stored so the UI can show which persona is active. */
    val personaId: String? = null,
    /** This agent's functional role label (e.g. "Devil's Advocate", "Optimist"). */
    val role: String = "",
    /** When true, this agent responds in Ponytail style — structured, bulleted, formal. */
    val ponytail: Boolean = false,
    /** Model sampling temperature (e.g. 0.0 to 2.0). Null = inherits global provider default. */
    val temperature: Double? = null,
    /** Model nucleus sampling top_p (e.g. 0.0 to 1.0). Null = inherits global provider default. */
    val topP: Double? = null,
    /** Model frequency penalty (-2.0 to 2.0). Null = inherits global config. */
    val frequencyPenalty: Double? = null,
    /** Model presence penalty (-2.0 to 2.0). Null = inherits global config. */
    val presencePenalty: Double? = null,
    /** Per-agent sampling overrides across deliberation rounds. */
    val samplingOverride: SamplingConfig? = null,
    /** Maximum completion tokens generated per turn. Null = inherits global provider default. */
    val maxTokens: Int? = null,
    /** Unique immutable seat identifier within this debate (e.g., "seat_0", "seat_1", "seat_moderator"). */
    val id: String = "seat_${kotlin.random.Random.nextInt(10000, 99999)}",
    /** When non-null, explicitly overrides discussion WebSearch permission for this agent seat. */
    val allowWebSearch: Boolean? = null,
)

/** The seat that moderates and writes the wrap-up: the configured seat, else the first seat of the
 * configured provider (old discussions), else the primary agent. */
fun DebateConfig.moderatorAgent(): Agent =
    moderation.moderatorSeatId?.let { id -> agents.firstOrNull { it.id == id } }
        ?: moderation.moderatorProvider?.let { p -> agents.firstOrNull { it.provider == p } }
        ?: primary

/** What this seat is actually called — the whole reason [displayName] exists instead of
 * just always using [Provider.brandName]. */
fun Agent.label(): String = displayName.ifBlank { provider.brandName() }

/** True when this agent seat wrote the message. Matched by seat ID so several personas on one
 * provider (one API key) stay distinct; the provider only matches old messages with no seat ID. */
fun DebateMessage.isFrom(agent: Agent): Boolean =
    if (seatId.isNotBlank()) seatId == agent.id else agentId == agent.provider

/** The name a transcript line is attributed to: the seat's display name, else the provider. */
fun DebateMessage.speaker(): String = authorDisplayName.ifBlank { agentId.name }

/** Source of the LLM model used for shared memory compression or deliverable synthesis. */
@Serializable
enum class ModelSource(val label: String) {
    COMPACTION_MODEL("Compaction Model (Haiku / Fast)"),
    MODERATOR_MODEL("Moderator Model (Primary)"),
    CUSTOM("Custom Model"),
}

/**
 * Defines the degree of human intervention in the discussion lifecycle.
 */
@Serializable
enum class UserInterventionPolicy(val label: String, val description: String) {
    /**
     * Completely User-Less / Autonomous Autopilot:
     * Debate runs continuously from round 1 to conclusion without requiring any user input.
     * Deadlocks are handled automatically by the AI moderator, deliverables are synthesized,
     * and artifacts are automatically saved.
     */
    AUTONOMOUS_AUTOPILOT(
        label = "Completely User-Less (Autopilot)",
        description = "Runs autonomously from start to finish. Auto-moderates loops, synthesizes deliverables, and auto-saves artifacts."
    ),

    /**
     * Observer with Human Injection:
     * Debate runs continuously by default, but human observer can inject comments/points at any time.
     */
    OBSERVER_INTERACTIVE(
        label = "Observer with Comments",
        description = "Debate proceeds continuously while allowing user to inject comments, objections, or guiding points anytime."
    ),

    /**
     * Human Gatekeeper:
     * Halts after agent turns/rounds and awaits explicit human review or approval before proceeding.
     */
    HUMAN_GATEKEEPER(
        label = "Human Gatekeeper",
        description = "Requires human review and approval after each round/turn before agents commit responses to shared memory."
    ),
}

@Serializable
enum class TokenBudgetAction(val label: String, val description: String) {
    WARNING(
        label = "Warning Alert (Recommended)",
        description = "Displays progressive token warnings without terminating the deliberation, allowing long multi-round debates to complete uninterrupted."
    ),
    HARD_STOP(
        label = "Hard Stop",
        description = "Immediately halts the deliberation and marks it with an error when the token budget ceiling is reached."
    ),
}

@Serializable
enum class ConsensusMode {
    /** Requires 100% of participants to agree. */
    UNANIMOUS,
    /** Requires >= 66% (or configurable tolerance) of participants to agree. */
    SUPERMAJORITY,
    /** Requires > 50% of participants to agree. */
    SIMPLE_MAJORITY,
    /** Consensus detection disabled; runs full fixed rounds unless manually stopped. */
    DISABLED
}

@Serializable
enum class ConsensusStrategy {
    /** Checks normalized prefix (e.g., "AGREED:", "CONCUR:", "[AGREED]"). */
    PREFIX_AND_PATTERN,
    /** Uses lightweight heuristic extraction + fuzzy pattern matching. */
    HEURISTIC_HYBRID,
    /** Uses a fast, low-cost classifier model (e.g., Haiku/Flash) to audit agreement. */
    MODEL_CLASSIFIED
}

@Serializable
data class ConsensusConfig(
    val mode: ConsensusMode = ConsensusMode.UNANIMOUS,
    val strategy: ConsensusStrategy = ConsensusStrategy.PREFIX_AND_PATTERN,
    /** Minimum rounds that must complete before consensus can trigger early exit (prevents premature 1-round bailout). */
    val minRoundsBeforeExit: Int = 2,
    /** Percentage threshold (0.5 to 1.0) when SUPERMAJORITY or tolerance mode is used. */
    val consensusThreshold: Double = 1.0,
    /** If true, checks consensus after every turn; if false, only at round boundaries. */
    val allowMidRoundTermination: Boolean = true,
    /** Fallback model used when strategy == MODEL_CLASSIFIED. */
    val classifierModel: String = "claude-haiku-4-5-20251001"
)

@Serializable
enum class AgentStance {
    DISAGREE,
    PARTIAL_CONVERGENCE,
    AGREED,
    CONCEDED
}

@Serializable
data class ParticipantConsensusState(
    val seatId: String,          // Unique ID per seat (e.g., "seat_0", "seat_1")
    val stance: AgentStance,
    val roundRecorded: Int,
    val agreementRationale: String? = null
)

sealed interface ConsensusEvaluationResult {
    data class NotReady(val reason: String) : ConsensusEvaluationResult
    data class Ongoing(val agreedCount: Int, val totalCount: Int) : ConsensusEvaluationResult
    data class Achieved(
        val agreedSeats: List<String>,
        val round: Int,
        val ratio: Double
    ) : ConsensusEvaluationResult
}

/**
 * Encapsulates the complete suite of debate execution policies.
 * Used for 3-tier cascade inheritance:
 * 1. Global App Settings (default policy)
 * 2. Project Settings (project-level override or inheritance)
 * 3. Discussion Settings (per-debate configuration)
 */
const val DEFAULT_HUMAN_DIALOGUE_DIRECTIVE =
    "Dialogue mode: 2–4 natural sentences per turn. No pleasantries, no markdown headers/bullets, no recaps. " +
        "Address others directly by name (@Agent). Disagree or challenge directly."

const val DEFAULT_TOPIC_DRIFT_DIRECTIVE =
    "Stay strictly anchored to the core question and topic. Do not digress into peripheral tangents, " +
        "speculative rabbit holes, or pedantic semantic debates. If another participant veers off-topic, " +
        "explicitly steer the discussion back to the core decision."

@Serializable
data class DebatePolicy(
    val userInterventionPolicy: UserInterventionPolicy = UserInterventionPolicy.AUTONOMOUS_AUTOPILOT,
    val tokenBudgetAction: TokenBudgetAction = TokenBudgetAction.WARNING,
    /** When true, forces conversational 2–4 sentence turns without robotic pleasantries, headers, or monologues. */
    val humanDialogueMode: Boolean = true,
    /** Custom directive text for human dialogue / anti-fluff. Configurable only in Global Settings. Blank = system default. */
    val humanDialogueDirective: String = "",
    val consensus: ConsensusConfig = ConsensusConfig(),
    val sharedMemory: SharedMemoryConfig = SharedMemoryConfig(),
    val depth: DepthConfig = DepthConfig(),
    val moderation: ModerationConfig = ModerationConfig(),
    val deliverable: DeliverableConfig = DeliverableConfig(),
    val output: OutputConfig = OutputConfig(),
    val permissions: PermissionConfig = PermissionConfig(),
    val costEfficiency: CostEfficiencyConfig = CostEfficiencyConfig(),
    val antiLoop: AntiLoopConfig = AntiLoopConfig(),
    val sampling: SamplingConfig = SamplingConfig(),
)

@Serializable
enum class DepthMode {
    CASUAL,
    EXECUTIVE,
    ACADEMIC,
    CUSTOM;

    val label: String
        get() = when (this) {
            CASUAL -> "Casual (Quick Take)"
            EXECUTIVE -> "Executive (Strategic)"
            ACADEMIC -> "Academic (First-Principles)"
            CUSTOM -> "Custom"
        }
}

@Serializable
data class DepthConfig(
    val mode: DepthMode = DepthMode.EXECUTIVE,
    /** Maximum allowable words per turn (enforced in directives and API max_tokens). */
    val targetWordCountPerTurn: Int = 350,
    /** Whether LaTeX math formatting ($...$, $$...$$) is permitted. */
    val allowMathFormulas: Boolean = false,
    /** Whether specialized academic/technical jargon is permitted without immediate plain-English translation. */
    val allowAcademicJargon: Boolean = false,
    /** Target reading ease: CASUAL = 70-80 (Grade 7-8), EXECUTIVE = 50-60 (Grade 10-12), ACADEMIC = 20-30 (Post-grad). */
    val targetFleschReadingEase: Int = 55,
    /** Recommended default rounds for this mode. */
    val recommendedRounds: Int = 3
) {
    companion object {
        fun preset(mode: DepthMode): DepthConfig = when (mode) {
            DepthMode.CASUAL -> DepthConfig(
                mode = DepthMode.CASUAL,
                targetWordCountPerTurn = 150,
                allowMathFormulas = false,
                allowAcademicJargon = false,
                targetFleschReadingEase = 75,
                recommendedRounds = 2
            )
            DepthMode.EXECUTIVE -> DepthConfig(
                mode = DepthMode.EXECUTIVE,
                targetWordCountPerTurn = 350,
                allowMathFormulas = false,
                allowAcademicJargon = false,
                targetFleschReadingEase = 55,
                recommendedRounds = 3
            )
            DepthMode.ACADEMIC -> DepthConfig(
                mode = DepthMode.ACADEMIC,
                targetWordCountPerTurn = 750,
                allowMathFormulas = true,
                allowAcademicJargon = true,
                targetFleschReadingEase = 25,
                recommendedRounds = 4
            )
            DepthMode.CUSTOM -> DepthConfig(
                mode = DepthMode.CUSTOM,
                targetWordCountPerTurn = 350,
                allowMathFormulas = false,
                allowAcademicJargon = false,
                targetFleschReadingEase = 55,
                recommendedRounds = 3
            )
        }
    }
}

/**
 * Controls how the debate's shared knowledge is compressed between turns to save tokens.
 * When enabled, the engine maintains a rolling summary of key points rather than passing
 * the full transcript to every agent on every turn.
 */
@Serializable
data class SharedMemoryConfig(
    val enabled: Boolean = false,
    val modelSource: ModelSource = ModelSource.COMPACTION_MODEL,
    /** Cheap/fast model used to produce per-turn knowledge deltas. */
    val summaryModel: String = "claude-haiku-4-5-20251001",
    /** Hard cap on the accumulated shared summary (in tokens). */
    val maxSummaryTokens: Int = 600,
    /** Always include the agent's own last response (preserves continuity). */
    val includeOwnLastTurn: Boolean = true,
    /** Always send Round 1 verbatim as a context anchor for all agents. */
    val includeFullRound1: Boolean = true,
)

/** How aggressively the moderator intervenes when loop / deadlock is detected. */
@Serializable
enum class InterventionStyle {
    /** "You've repeated this. Introduce a new angle or concede." */
    REDIRECT,
    /** "Provide one concrete counter-example or retract your position." */
    CHALLENGE,
    /** "State your final position and score 1–10 your confidence." */
    FORCE_VOTE,
}

@Serializable
enum class ModerationStyle {
    PASSIVE_WRAPUP_ONLY,
    PERIODIC_CHECKPOINT,
    DYNAMIC_ACTIVE_STEERAGE,
    STRICT_ARBITRATION;

    val label: String
        get() = when (this) {
            PASSIVE_WRAPUP_ONLY -> "Passive Wrap-Up Only"
            PERIODIC_CHECKPOINT -> "Periodic Checkpoint (Every N Rounds)"
            DYNAMIC_ACTIVE_STEERAGE -> "Dynamic Active Steerage (Recommended)"
            STRICT_ARBITRATION -> "Strict Parliamentary Arbitration"
        }
}

@Serializable
enum class ModeratorPersona {
    DELIBERATION_CHAIR,      // Neutral, structured, parliamentary
    EXECUTIVE_ARBITER,       // Pragmatic, ROI-driven, cuts through fluff
    SOCRATIC_PROBE,          // Questions unexamined assumptions
    DEVILS_ADVOCATE_CHAIR;   // Challenges emerging premature consensus

    val label: String
        get() = when (this) {
            DELIBERATION_CHAIR -> "Deliberation Chair (Parliamentary)"
            EXECUTIVE_ARBITER -> "Executive Arbiter (Pragmatic & ROI)"
            SOCRATIC_PROBE -> "Socratic Inquirer (Questions Assumptions)"
            DEVILS_ADVOCATE_CHAIR -> "Devil's Advocate Chair (Contrarian)"
        }
}

/**
 * Controls the moderator agent's dialectic steerage, checkpoints, and loop-detection behaviour.
 * The moderator can be any agent seat or the primary (default).
 */
@Serializable
data class ModerationConfig(
    val style: ModerationStyle = ModerationStyle.DYNAMIC_ACTIVE_STEERAGE,
    val persona: ModeratorPersona = ModeratorPersona.EXECUTIVE_ARBITER,
    /** Dedicated model used for moderation turns (defaults to cheap/fast tier-2 model). */
    val moderatorModel: String = "claude-haiku-4-5-20251001",
    /** Number of rounds between interventions in PERIODIC mode (default: 2). */
    val checkpointFrequencyRounds: Int = 2,
    /** Minimum topical similarity score before triggering a drift intervention (0.0 to 1.0). */
    val driftThreshold: Double = 0.45,
    /** Injects the moderator's steerage directive directly into the agents' next-turn instructions. */
    val enforceSteerageDirectives: Boolean = true,
    val enabled: Boolean = false,
    /** Which provider acts as moderator. null = primary agent. Kept for old discussions. */
    val moderatorProvider: Provider? = null,
    /** Which seat acts as moderator, so one persona among several on the same provider can.
     * Wins over [moderatorProvider]. null = fall back to it, then the primary agent. */
    val moderatorSeatId: String? = null,
    /**
     * Strictness 1–5:
     *  1 = Passive (only breaks infinite loops, 5+ turns)
     *  2 = Moderate (allows 4 turns before stepping in)
     *  3 = Balanced (default: intervenes after 3 turns with no novelty)
     *  4 = Assertive (intervenes after 2 turns)
     *  5 = Ruthless (zero repetition tolerance, steps in after 1 repeated turn)
     */
    val strictness: Int = 3,
    /** Consecutive turns with no semantic novelty before moderator steps in. */
    val loopDetectionThreshold: Int = 3,
    /** How the moderator intervenes when a loop is detected. */
    val interventionStyle: InterventionStyle = InterventionStyle.REDIRECT,
    /** Max moderator interventions per debate before forcing conclusion. */
    val maxInterventions: Int = 5,
    /** Granular triggers */
    val detectRepetition: Boolean = true,
    val detectTopicDrift: Boolean = true,
    /** Custom directive text for topic drift / anti-rabbit-hole. Configurable only in Global Settings. Blank = system default. */
    val topicDriftDirective: String = "",
    val enforceEvidence: Boolean = false,
    val enforceCivility: Boolean = true,
    /** Custom directives/prompt given specifically to the moderator agent. */
    val customDirectives: String = "",
)

typealias ModeratorConfig = ModerationConfig

/** The structured output format for the debate's deliverable artifact. */
@Serializable
enum class DeliverableFormat {
    /** "Decision: X. Rationale: Y. Risks: Z." — default concise output. */
    DECISION_SUMMARY,
    /** Numbered action steps with suggested owners and timelines. */
    ACTION_PLAN,
    /** Tabular pros/cons/weight/score per option considered. */
    DECISION_MATRIX,
    /** Simple structured For / Against list with weight. */
    PRO_CON_LIST,
    /** 1-page brief: context, options evaluated, recommendation, next steps. */
    EXECUTIVE_BRIEF,
    /** Verbatim custom prompt drives the deliverable generation. */
    CUSTOM,
}

/**
 * Controls the structured deliverable artifact generated at debate end.
 * Separate from the colloquial summary — this is the actionable output.
 */
@Serializable
data class DeliverableConfig(
    val format: DeliverableFormat = DeliverableFormat.DECISION_SUMMARY,
    /** Custom prompt appended to (or replacing, for CUSTOM format) the format template. */
    val customInstructions: String = "",
    val modelSource: ModelSource = ModelSource.COMPACTION_MODEL,
    val customModel: String = "",
    /** Which agent generates the deliverable. null = primary/moderator. */
    val generatorProvider: Provider? = null,
)

/**
 * Controls automatic file export when a debate completes.
 * All auto-saves write to [outputFolder]; individual flags control what is saved.
 */
@Serializable
data class OutputConfig(
    /** Master switch to save / not save debate artifacts. */
    val saveArtifacts: Boolean = true,
    /** Absolute folder path for auto-saves. Empty string = auto-save on engine side. */
    val outputFolder: String = "",
    /** Auto-save the full transcript as Markdown when the debate finishes. */
    val autoSaveFullTranscript: Boolean = true,
    /** Auto-save the colloquial summary only. */
    val autoSaveSummary: Boolean = true,
    /** Auto-save the structured deliverable artifact (on demand by default). */
    val autoSaveDeliverable: Boolean = false,
    /**
     * File naming pattern. Supports tokens: {date}, {topic}, {status}.
     * Example: "{date}-{topic}" → "2026-08-31-is-dialex-a-good-name"
     */
    val fileNamePattern: String = "{date}-{topic}",
    /** When true, append to an existing file rather than overwrite. */
    val appendMode: Boolean = false,
)

/**
 * Config shared by every agent. [primary] always takes part, always speaks first each
 * round, and gives the final decision — configurable to any provider, defaults to Claude.
 * Up to 5 additional seats are optional extras — 6 participants total per spec. Each seat's
 * provider is independently pickable, and seats may share a provider.
 */
/** Guards against conformity, which studies find drives accuracy down in multi-agent debate,
 * especially with several personas on one model. Mirrors the Go engine's IndependenceConfig. */
@Serializable
data class IndependenceConfig(
    /** Round 1: each seat answers without seeing the other seats' round-1 answers. */
    val blindFirstRound: Boolean = false,
    /** Other seats appear as "Participant A/B/C" instead of by name. A persona that names itself
     * in its text still leaks its identity; this removes the labels, not the content. */
    val anonymizeTranscript: Boolean = false,
)

@Serializable
data class DebateConfig(
    val topic: String,
    val commonContext: String = "",
    /** Third shared card — extra background/data beyond the topic and context. */
    val commonInfo: String = "",
    // Old field names kept as the wire format (@SerialName) so discussions saved before
    // this rename still deserialize instead of crashing on load.
    @SerialName("claude") val primary: Agent,
    @SerialName("gemini") val secondary: Agent? = null,
    @SerialName("chatgpt") val tertiary: Agent? = null,
    val quaternary: Agent? = null,
    val quinary: Agent? = null,
    /** Sixth optional seat — added in spec v2 (6-agent expansion). */
    val senary: Agent? = null,
    val roundMode: RoundMode = RoundMode.FIXED,
    /** Only used when roundMode == FIXED. */
    val maxRounds: Int = 10,
    /** Master instructions template, frozen as a snapshot when the discussion is created.
     * Overrides the global masterInstructions from [AppState] for this specific discussion. */
    val masterInstructions: String = "",
    /** How close to 100% unanimous agreement the discussion must reach before declaring
     * early consensus (1.0 = all agents, 0.8 = 80% etc.). Defaults to 1.0. */
    val consensusTolerance: Double = 1.0,
    /** Dynamic consensus detection and early stopping configuration. */
    val consensus: ConsensusConfig = ConsensusConfig(consensusThreshold = consensusTolerance),
    /** When true, an "AGREED:" prefix triggers a second round of objection checking before
     * early termination is accepted. */
    val validateObjections: Boolean = false,
    /** Files attached to this debate configuration for AI context injection. */
    val attachedFiles: List<AttachedFile> = emptyList(),
    /** Shared-memory / token-compression strategy for long debates. */
    val sharedMemory: SharedMemoryConfig = SharedMemoryConfig(),
    /** Deliberation depth & cognitive load configuration. */
    val depth: DepthConfig = DepthConfig(),
    /** Moderator agent config — dynamic dialectic steerage & checkpoints. */
    val moderation: ModerationConfig = ModerationConfig(),
    /** How much agents see of each other: blind first round, anonymized peers. */
    val independence: IndependenceConfig = IndependenceConfig(),
    /** Deliverable artifact config — format and generator. */
    val deliverable: DeliverableConfig = DeliverableConfig(),
    /** Output & auto-save config — folder and file-save flags. */
    val output: OutputConfig = OutputConfig(),
    /** Human intervention policy (Autonomous Autopilot, Observer, or Gatekeeper). */
    val userInterventionPolicy: UserInterventionPolicy = UserInterventionPolicy.AUTONOMOUS_AUTOPILOT,
    /** Granular tool and execution permissions. */
    val permissions: PermissionConfig = PermissionConfig(),
    /** Action to take when token budget is reached (Warning alert vs Hard Stop). */
    val tokenBudgetAction: TokenBudgetAction = TokenBudgetAction.WARNING,
    /** When true, forces conversational 2–4 sentence turns without robotic pleasantries, headers, or monologues. */
    val humanDialogueMode: Boolean = true,
    /** Custom directive text for human dialogue / anti-fluff. Configurable only in Global Settings. Blank = system default. */
    val humanDialogueDirective: String = "",
    /** Cost efficiency & dynamic context compaction configuration. */
    val costEfficiency: CostEfficiencyConfig = CostEfficiencyConfig(),
    /** Anti-looping and content deduplication guards configuration. */
    val antiLoop: AntiLoopConfig = AntiLoopConfig(),
    /** Granular temperature and sampling controls configuration. */
    val sampling: SamplingConfig = SamplingConfig(),
) {
    /** Speaking order — primary first always, then whichever others are included, in the
     * order they were added. */
    val agents: List<Agent>
        get() = listOfNotNull(primary, secondary, tertiary, quaternary, quinary, senary)
}

@Serializable
data class CommandRequest(
    val commandId: String,
    val command: String,
    val workingDirectory: String = "",
    val isDestructive: Boolean = false,
    val isApproved: Boolean? = null, // null = pending, true = approved, false = rejected
    val output: String? = null,
)

@Serializable
data class DebateMessage(
    /** The provider that spoke. Not unique per debate (seats can share a provider): use [seatId] for identity. */
    val agentId: Provider = Provider.CUSTOM,
    val round: Int = 0,
    val content: String = "",
    /** Unused by current runs — kept so old persisted transcripts still deserialize. */
    val isFinalOpinion: Boolean = false,
    /** True when `content` is an error (a turn failed) rather than an agent's reply. */
    val isError: Boolean = false,
    /** Terminal execution command requested by the agent during this turn. */
    val commandRequest: CommandRequest? = null,
    /** Best-effort — only API providers report usage; CLI turns leave these null. */
    val tokensIn: Int? = null,
    val tokensOut: Int? = null,
    val tokensCached: Int? = null,
    /** True when this turn is a moderator intervention (loop breaking / redirect / challenge). */
    val isModeratorIntervention: Boolean = false,
    /** True when this message was injected by the human user/participant as a comment. */
    val isUserComment: Boolean = false,
    /** Timestamp in epoch milliseconds when this message was generated. */
    val timestampMs: Long = 0L,
    /** Unique seat ID of the authoring agent. */
    val seatId: String = "",
    /** Provider used to generate this turn (telemetry only, not identity). */
    val provider: Provider = agentId,
    /** Cached snapshot of the agent's display label at the time of generation. */
    val authorDisplayName: String = "",
    /** True when this turn is a system annotation or status notification. */
    val isSystem: Boolean = false,
    /** True when this turn was recovered from a detected loop via anti-loop retry. */
    val isLoopRecovered: Boolean = false,
    /** True when this turn was converted into an auto-concession by the anti-loop circuit breaker. */
    val isStalledConcession: Boolean = false,
    /** True when the engine judged this agent turn to be in agreement with the council. */
    val agreed: Boolean = false,
) {
    constructor(
        seatId: String,
        provider: Provider,
        authorDisplayName: String,
        round: Int,
        content: String,
        isError: Boolean = false,
        isSystem: Boolean = false,
        isModeratorIntervention: Boolean = false,
        tokensIn: Int? = null,
        tokensOut: Int? = null,
        tokensCached: Int? = null,
        timestampMs: Long = 0L,
        commandRequest: CommandRequest? = null,
        isUserComment: Boolean = false,
        isFinalOpinion: Boolean = false,
        isLoopRecovered: Boolean = false,
        isStalledConcession: Boolean = false,
    ) : this(
        agentId = provider,
        round = round,
        content = content,
        isFinalOpinion = isFinalOpinion,
        isError = isError,
        commandRequest = commandRequest,
        tokensIn = tokensIn,
        tokensOut = tokensOut,
        tokensCached = tokensCached,
        isModeratorIntervention = isModeratorIntervention,
        isUserComment = isUserComment,
        timestampMs = timestampMs,
        seatId = seatId,
        provider = provider,
        authorDisplayName = authorDisplayName,
        isSystem = isSystem,
        isLoopRecovered = isLoopRecovered,
        isStalledConcession = isStalledConcession,
    )
}

@Serializable
data class DebateResult(
    val transcript: List<DebateMessage>,
    /** Single verdict from Claude — only produced when FIXED rounds complete naturally. */
    val conclusion: String?,
    /** Set when a turn failed and ended the debate early. */
    val error: String? = null,
    /** True when Pause halted the debate mid-flight — resumable via the same transcript. */
    val paused: Boolean = false,
    /** Set when emergency synthesis salvaged an outcome after a turn failure. */
    val warning: String? = null,
    /** True when early consensus termination was triggered. */
    val isConsensusReached: Boolean = false,
    /** Reason for early exit (e.g. "CONSENSUS", "TOKEN_BUDGET", etc.). */
    val earlyExitReason: String? = null,
)

object ContextGuard {
    val PROVIDER_MAX_INPUT_LIMITS = mapOf(
        Provider.ANTHROPIC to 190_000,
        Provider.OPENAI to 120_000,
        Provider.GEMINI to 500_000,
        Provider.OLLAMA to 16_000,
    )

    fun ensurePayloadWithinCeiling(
        provider: Provider,
        transcript: List<DebateMessage>,
        compactor: (suspend (List<DebateMessage>) -> List<DebateMessage>)? = null,
    ): List<DebateMessage> {
        val maxSafeTokens = PROVIDER_MAX_INPUT_LIMITS[provider] ?: 64_000
        val estimatedTokens = transcript.sumOf { (it.tokensOut ?: (it.content.length / 4)) }

        if (estimatedTokens > maxSafeTokens * 0.85) {
            // Pre-emptively trigger emergency pruning / compaction
            return transcript.takeLast(6) // Emergency fall-back to recent turns
        }
        return transcript
    }
}
