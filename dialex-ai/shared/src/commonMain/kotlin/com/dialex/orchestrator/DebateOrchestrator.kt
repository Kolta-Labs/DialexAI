package com.dialex.orchestrator

import com.dialex.model.Agent
import com.dialex.model.CompactionStrategy
import com.dialex.model.ConsensusEvaluationResult
import com.dialex.model.DebateConfig
import com.dialex.model.DebateMessage
import com.dialex.model.DebateResult
import com.dialex.model.LoopAction
import com.dialex.model.LoopInspectionResult
import com.dialex.model.PricingTable
import com.dialex.model.RoundMode
import com.dialex.model.TokenBudgetAction
import com.dialex.model.label
import com.dialex.runner.AgentReply
import com.dialex.runner.AgentRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private const val LENGTH_BUDGET_DIRECTIVE = """
[LENGTH & BUDGET CONSTRAINT]
- Your response MUST NOT exceed 350 words.
- Be dense, high-signal, and direct.
- Omit conversational pleasantries, introductory recaps, and decorative preamble.
"""

private const val ANTI_LOOP_DIRECTIVE = """
[CRITICAL SYSTEM DIRECTIVE: REPETITION DETECTED]
Your prior draft was rejected because it duplicated previous debate arguments (>65% identical content or verbatim sections).
You MUST:
1. Provide completely new empirical evidence, unaddressed counter-arguments, or novel perspective.
2. DO NOT re-state your thesis or repeat your prior examples.
3. If you have nothing substantively new to contribute, you must cleanly concede or state "AGREED:" followed by your 1-2 sentence core reason.
"""

private const val HUMAN_DIALOGUE_DIRECTIVE =
    "Dialogue mode: 2–4 natural sentences per turn. No pleasantries, no markdown headers/bullets, no recaps. " +
        "Address others directly by name (@Agent). Disagree or challenge directly."

private const val CONSENSUS_DIRECTIVE = """
[DELIBERATION CONVERGENCE RULES]
1. If the arguments presented by other participants have resolved the core contentions and you have no substantially new empirical data, theoretical models, or counter-arguments to introduce:
   - Begin your reply immediately with the single token: AGREED:
   - Provide a concise 2-4 sentence summary explaining why you concur.
2. DO NOT artificially prolong the debate if consensus is reached.
3. DO NOT concede simply to end the debate if you still have an unaddressed factual, ethical, or logical objection.
"""

private const val CASUAL_DIRECTIVE = """
[COGNITIVE LOAD DIRECTIVE: CASUAL / ACCESSIBLE]
- Target Audience: General readers and everyday users.
- Vocabulary: Plain, clear, everyday English (8th-grade reading level).
- STRICT PROHIBITIONS:
  * NO mathematical equations, LaTeX symbols, or formulas ($...$, \frac, etc.).
  * NO academic or sociological jargon (e.g., do not use terms like "non-ergodic", "reification", "teleology", "epicycles").
  * NO Latin phrases or formal academic citations.
- Tone: Conversational, engaging, and direct. Explain complex ideas using simple, real-world analogies (like cooking, sports, or driving).
- Length: Maximum 150 words per turn. Keep it punchy and enjoyable to read.
"""

private const val EXECUTIVE_DIRECTIVE = """
[COGNITIVE LOAD DIRECTIVE: EXECUTIVE / STRATEGIC]
- Target Audience: Business leaders, engineering executives, and decision-makers.
- Tone: High-signal, authoritative, pragmatic, and ROI-focused.
- Formatting:
  * Start with a 1-sentence bottom-line takeaway in bold.
  * Provide 2-3 concise bullet points with concrete evidence, organizational trade-offs, and operational risks.
  * Omit philosophical ruminations and decorative conversational preamble.
- Vocabulary: Standard professional business and technical vocabulary. Avoid gratuitous mathematical abstraction.
- Length: Maximum 300 words per turn.
"""

private const val ACADEMIC_DIRECTIVE = """
[COGNITIVE LOAD DIRECTIVE: ACADEMIC / FIRST-PRINCIPLES]
- Target Audience: Theoretical scientists, researchers, and domain scholars.
- Tone: Rigorous, analytical, epistemologically grounded, and unsparing in formal precision.
- Expectations:
  * Derive claims from first principles and domain invariants.
  * Utilize formal mathematical notations (LaTeX) and foundational theorems where appropriate.
  * Situate arguments within established economic, cybernetic, sociological, or physical frameworks.
- Length: Maximum 750 words per turn. Dense, rigorous, and logically structured.
"""

private fun buildCustomDepthDirective(depth: com.dialex.model.DepthConfig): String = buildString {
    appendLine("[COGNITIVE LOAD DIRECTIVE: CUSTOM]")
    appendLine("- Maximum words per turn: ${depth.targetWordCountPerTurn} words.")
    if (!depth.allowMathFormulas) {
        appendLine("- STRICT PROHIBITION: NO mathematical formulas, LaTeX symbols, or equations.")
    } else {
        appendLine("- Mathematical formulas and LaTeX notations are permitted.")
    }
    if (!depth.allowAcademicJargon) {
        appendLine("- STRICT PROHIBITION: NO obscure academic jargon without plain explanation.")
    } else {
        appendLine("- Technical and domain-specific terminology is permitted.")
    }
    appendLine("- Target Reading Ease: ${depth.targetFleschReadingEase} (0-100 scale).")
}


private const val COMPACT_THRESHOLD = 20
private const val COMPACT_KEEP_RECENT = 8
private const val COMPACT_DIRECTIVE =
    "Summarize this debate so far concisely but thoroughly: preserve every participant's key " +
        "positions, strongest arguments, and how the discussion evolved. This summary will " +
        "replace the verbatim transcript for future turns, so don't lose anything important — " +
        "but be much shorter than the original."

private const val DEFAULT_COMPACT_MODEL = "claude-haiku-4-5-20251001"
private const val DEFAULT_TOKEN_BUDGET = 1_000_000
private const val RETRY_DELAY_MS = 1500L

private suspend fun AgentRunner.respondWithRetry(
    agent: Agent,
    topic: String,
    commonContext: String,
    commonInstructions: String,
    transcript: List<DebateMessage>,
    modelOverride: String? = null,
): AgentReply {
    var quotaAttempts = 0
    val maxQuotaAttempts = 3
    var normalAttempt = 0
    val maxNormalAttempts = 1

    while (true) {
        try {
            return respond(agent, topic, commonContext, commonInstructions, transcript, modelOverride)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            val msg = t.message ?: ""
            val isRateLimitOrQuota = msg.contains("429") ||
                msg.contains("rate limit", ignoreCase = true) ||
                msg.contains("quota", ignoreCase = true) ||
                msg.contains("resource exhausted", ignoreCase = true)

            if (isRateLimitOrQuota) {
                quotaAttempts++
                if (quotaAttempts <= maxQuotaAttempts) {
                    val parsedSec = Regex("""(\d+)\s*(?:seconds|s)""").find(msg)?.groupValues?.get(1)?.toLongOrNull()
                    val waitMs = ((parsedSec ?: (quotaAttempts * 4L)) * 1000L).coerceIn(2000L, 30_000L)
                    delay(waitMs)
                    continue
                }
            } else {
                normalAttempt++
                if (normalAttempt <= maxNormalAttempts) {
                    delay(RETRY_DELAY_MS)
                    continue
                }
            }
            throw t
        }
    }
}

/**
 * Round-robin turn loop with multi-layer cost efficiency, anti-looping guards,
 * and granular temperature/sampling controls.
 */
class DebateOrchestrator(
    private val runnerFor: (Agent) -> AgentRunner,
) {
    suspend fun run(
        config: DebateConfig,
        initialTranscript: List<DebateMessage> = emptyList(),
        isStopped: () -> Boolean = { false },
        compactionModel: String = DEFAULT_COMPACT_MODEL,
        tokenBudget: Int = DEFAULT_TOKEN_BUDGET,
        onMessage: (DebateMessage) -> Unit = {},
    ): DebateResult {
        val transcript = initialTranscript.toMutableList()
        val effectiveTopic = com.dialex.util.AttachmentTokenOptimizer.formatWithAttachments(
            baseText = config.topic,
            files = config.attachedFiles.filter { it.scope.equals("topic", ignoreCase = true) }
        )
        val effectiveContext = com.dialex.util.AttachmentTokenOptimizer.formatWithAttachments(
            baseText = config.commonContext,
            files = config.attachedFiles.filter { 
                it.scope.equals("common_context", ignoreCase = true) ||
                it.scope.equals("all", ignoreCase = true) ||
                it.scope.equals("global", ignoreCase = true) ||
                it.scope.equals("reference", ignoreCase = true) ||
                it.scope.isBlank()
            }
        )
        val effectiveInfo = com.dialex.util.AttachmentTokenOptimizer.formatWithAttachments(
            baseText = config.commonInfo,
            files = config.attachedFiles.filter { it.scope.equals("common_info", ignoreCase = true) }
        )

        val instructions = buildString {
            append(CONSENSUS_DIRECTIVE)
            if (config.humanDialogueMode) {
                val dir = config.humanDialogueDirective.ifBlank { com.dialex.model.DEFAULT_HUMAN_DIALOGUE_DIRECTIVE }
                append("\n\n$dir")
            }
            if (config.moderation.detectTopicDrift) {
                val dir = config.moderation.topicDriftDirective.ifBlank { com.dialex.model.DEFAULT_TOPIC_DRIFT_DIRECTIVE }
                append("\n\nTOPIC FOCUS & ANTI-RABBIT-HOLE GUARDRAIL:\n$dir")
            }
            if (effectiveInfo.isNotBlank()) append("\nAdditional information: $effectiveInfo")
            if (config.masterInstructions.isNotBlank()) append("\n${config.masterInstructions}")
        }

        // Shared Memory & Compaction state (TASK-03)
        val costEff = config.costEfficiency
        val useCostEff = costEff.enabled
        val sharedMem = config.sharedMemory
        val useSharedMem = sharedMem.enabled

        var sharedMemoryBlackboard: String? = null
        var compactedSummary: String? = null
        var compactedCount = 0
        var activeSteerageDirective = ""

        val summaryModelToUse = if (compactionModel != DEFAULT_COMPACT_MODEL) {
            compactionModel
        } else if (useCostEff && costEff.backgroundSummaryModel.isNotBlank()) {
            costEff.backgroundSummaryModel
        } else if (sharedMem.summaryModel.isNotBlank()) {
            sharedMem.summaryModel
        } else {
            compactionModel
        }

        suspend fun maybeCompact(currentRound: Int = 1) {
            val uncompactedTurns = transcript.drop(compactedCount)
            val estimatedTokens = uncompactedTurns.sumOf { it.tokensOut ?: (it.content.length / 4) }
            val shouldTrigger = if (useCostEff) {
                estimatedTokens >= costEff.triggerTokenThreshold || (transcript.size - compactedCount > COMPACT_THRESHOLD)
            } else {
                transcript.size - compactedCount > COMPACT_THRESHOLD
            }

            if (!shouldTrigger) return

            val isBlackboardStrategy = (useCostEff && (costEff.strategy == CompactionStrategy.HYBRID_BLACKBOARD_RECENT || costEff.strategy == CompactionStrategy.STRUCTURED_BLACKBOARD)) || useSharedMem

            if (isBlackboardStrategy) {
                val cutoff = transcript.size - costEff.keepRecentVerbatimTurns.coerceAtLeast(1)
                if (cutoff <= compactedCount) return
                val maxWords = if (costEff.maxOutputTokensPerTurn > 0) costEff.maxOutputTokensPerTurn else 500
                val summaryAgent = config.primary.copy(
                    systemPrompt = """
                    You are the debate knowledge synthesizer. Maintain and update the Structured Shared Memory Blackboard of this debate.
                    Output strictly in this structured markdown format (under $maxWords words total, eliminate conversational pleasantries):
                    
                    [SHARED MEMORY BLACKBOARD — ROUND $currentRound]
                    • TOPIC ESSENCE: One sentence defining the core question in dispute.
                    • ESTABLISHED PREMISES:
                      - Verified fact 1
                      - Verified constraint 2
                    • CURRENT FACTIONS & POSITIONS:
                      - Each participant's primary stance and core supporting argument.
                    • ACTIVE UNRESOLVED CONTENTIONS:
                      - Active disagreements, trade-offs, or disputed claims.
                    """.trimIndent()
                )
                try {
                    val summary = runnerFor(summaryAgent).respond(
                        agent = summaryAgent,
                        topic = effectiveTopic,
                        commonContext = effectiveContext,
                        commonInstructions = "",
                        transcript = transcript.take(cutoff),
                        modelOverride = summaryModelToUse,
                    ).content
                    sharedMemoryBlackboard = summary
                    compactedCount = cutoff
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Throwable) {
                    // Non-fatal optimization failure
                }
            } else {
                val keepRecent = COMPACT_KEEP_RECENT
                val cutoff = transcript.size - keepRecent
                if (cutoff <= compactedCount) return
                val older = transcript.take(cutoff)
                val compactionAgent = config.primary.copy(systemPrompt = COMPACT_DIRECTIVE)
                try {
                    val summary = runnerFor(compactionAgent).respond(
                        agent = compactionAgent,
                        topic = effectiveTopic,
                        commonContext = effectiveContext,
                        commonInstructions = "",
                        transcript = older,
                        modelOverride = summaryModelToUse,
                    ).content
                    compactedSummary = summary
                    compactedCount = cutoff
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Throwable) {
                    // Compaction is an optimization, not a requirement
                }
            }
        }

        maybeCompact()

        fun contextView(forAgent: Agent): List<DebateMessage> {
            val views = if (sharedMemoryBlackboard != null) {
                val list = mutableListOf<DebateMessage>()
                list.add(
                    DebateMessage(
                        seatId = config.primary.id,
                        provider = config.primary.provider,
                        authorDisplayName = config.primary.label(),
                        round = 0,
                        content = sharedMemoryBlackboard!!
                    )
                )
                if (sharedMem.enabled && sharedMem.includeFullRound1) {
                    list.addAll(transcript.filter { it.round == 1 && !it.isError })
                }
                if (sharedMem.enabled && sharedMem.includeOwnLastTurn) {
                    val ownLast = transcript.findLast { 
                        (it.seatId == forAgent.id || it.agentId == forAgent.provider) &&
                            !it.isError && (!sharedMem.includeFullRound1 || it.round > 1) 
                    }
                    if (ownLast != null && !list.contains(ownLast)) {
                        list.add(ownLast)
                    }
                }
                val recentCount = if (useCostEff) costEff.keepRecentVerbatimTurns.coerceAtLeast(1) else 2
                val recent = transcript.takeLast(recentCount)
                for (m in recent) {
                    if (!list.contains(m)) {
                        list.add(m)
                    }
                }
                list
            } else if (compactedSummary != null) {
                val recap = DebateMessage(
                    seatId = config.primary.id,
                    provider = config.primary.provider,
                    authorDisplayName = config.primary.label(),
                    round = 0,
                    content = "[Summary of earlier discussion]\n\n$compactedSummary"
                )
                listOf(recap) + transcript.drop(compactedCount)
            } else {
                transcript
            }
            return com.dialex.model.ContextGuard.ensurePayloadWithinCeiling(forAgent.provider, views)
        }

        val openingDirective = "Lay out the key points on the topic using the context and " +
            "information given, then explicitly ask the other participant(s) what they think."

        /**
         * Speaks with Anti-Loop Guard (TASK-02) and Granular Sampling Controls (TASK-05).
         */
        suspend fun speak(agent: Agent, round: Int): Pair<DebateMessage?, String?> {
            val antiLoop = config.antiLoop
            val priorAgentTurns = transcript.filter {
                (it.seatId == agent.id || it.agentId == agent.provider) && !it.isError && !it.isSystem && !it.isUserComment
            }.map { it.content }
            val lastSpeakerTurn = transcript.lastOrNull { !it.isError && !it.isSystem && !it.isUserComment }?.content
            val candidateTurns = if (lastSpeakerTurn != null && !priorAgentTurns.contains(lastSpeakerTurn)) {
                priorAgentTurns + lastSpeakerTurn
            } else {
                priorAgentTurns
            }

            var attempts = 0
            var tempBump = 0.0
            var freqBump = 0.0

            while (attempts <= (if (antiLoop.enabled) antiLoop.maxRetries else 0)) {
                val extraDirective = if (attempts > 0) "\n$ANTI_LOOP_DIRECTIVE" else ""

                val agentSampling = agent.samplingOverride ?: config.sampling.let { base ->
                    if (agent.temperature != null || agent.topP != null) {
                        base.copy(
                            temperature = agent.temperature ?: base.temperature,
                            startTemperature = agent.temperature ?: base.startTemperature,
                            floorTemperature = if (agent.temperature != null) agent.temperature!! else base.floorTemperature,
                            topP = agent.topP ?: base.topP
                        )
                    } else {
                        base
                    }
                }
                val normalizedSampling = SamplingNormalizer.normalize(
                    config = agentSampling,
                    provider = agent.provider,
                    round = round,
                    maxRounds = if (config.roundMode == RoundMode.FIXED) config.maxRounds else 10,
                    temperatureBump = tempBump,
                    frequencyPenaltyBump = freqBump
                )

                val isOpeningTurn = transcript.isEmpty() && agent.provider == config.primary.provider
                val parts = mutableListOf<String>()
                val personaDirective = when {
                    agent.systemPrompt.isNotBlank() -> agent.systemPrompt
                    agent.personaId != null -> com.dialex.model.SystemPersonas.find { it.id == agent.personaId }?.systemPrompt.orEmpty()
                    else -> ""
                }
                if (personaDirective.isNotBlank()) parts.add(personaDirective)
                if (isOpeningTurn) parts.add(openingDirective)

                // Deliberation Depth & Cognitive Load Directive (TASK-04)
                when (config.depth.mode) {
                    com.dialex.model.DepthMode.CASUAL -> parts.add(CASUAL_DIRECTIVE)
                    com.dialex.model.DepthMode.EXECUTIVE -> parts.add(EXECUTIVE_DIRECTIVE)
                    com.dialex.model.DepthMode.ACADEMIC -> parts.add(ACADEMIC_DIRECTIVE)
                    com.dialex.model.DepthMode.CUSTOM -> parts.add(buildCustomDepthDirective(config.depth))
                }

                if (useCostEff) parts.add(LENGTH_BUDGET_DIRECTIVE)
                if (normalizedSampling.styleDirective != null) parts.add(normalizedSampling.styleDirective)

                // WebSearch permission directive
                val webSearchAllowed = agent.allowWebSearch ?: config.permissions.isWebSearchAllowedFor(agent.id)
                if (webSearchAllowed) {
                    parts.add("[LIVE WEB SEARCH & GROUNDING: ENABLED]\n- You have permission to search the web for live documentation, factual verification, library versions, and external resources.\n- When citing external facts or domain checks, explicitly cite your source or domain.")
                } else {
                    parts.add("[LIVE WEB SEARCH: DISABLED]\n- Web search is disabled for your seat in this deliberation.\n- Rely strictly on the attached workspace files and your existing parametric knowledge.")
                }

                if (activeSteerageDirective.isNotBlank()) {
                    parts.add("[MANDATORY MODERATOR STEERAGE DIRECTIVE]\n$activeSteerageDirective")
                }

                if (extraDirective.isNotBlank()) parts.add(extraDirective)

                val effectiveAgentContext = com.dialex.util.AttachmentTokenOptimizer.formatWithAttachments(
                    baseText = agent.context,
                    files = config.attachedFiles.filter { it.scope == "agent_${agent.provider.name}" }
                )

                val depthTokenCap = (config.depth.targetWordCountPerTurn * 1.5).toInt()
                val baseTokensCeiling = agent.maxTokens ?: depthTokenCap
                val maxTokensCeiling = if (useCostEff) {
                    if (config.depth.mode == com.dialex.model.DepthMode.ACADEMIC) {
                        maxOf(baseTokensCeiling, costEff.maxOutputTokensPerTurn)
                    } else {
                        minOf(baseTokensCeiling, costEff.maxOutputTokensPerTurn)
                    }
                } else {
                    agent.maxTokens ?: depthTokenCap
                }

                val effectiveAgent = agent.copy(
                    systemPrompt = parts.joinToString("\n"),
                    context = effectiveAgentContext,
                    temperature = normalizedSampling.temperature,
                    topP = normalizedSampling.topP,
                    frequencyPenalty = normalizedSampling.frequencyPenalty,
                    presencePenalty = normalizedSampling.presencePenalty,
                    maxTokens = maxTokensCeiling
                )

                val reply = try {
                    runnerFor(effectiveAgent).respondWithRetry(
                        agent = effectiveAgent,
                        topic = effectiveTopic,
                        commonContext = effectiveContext,
                        commonInstructions = instructions,
                        transcript = contextView(effectiveAgent),
                    )
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    val message = t.message ?: t.toString()
                    val msg = DebateMessage(
                        seatId = agent.id,
                        provider = agent.provider,
                        authorDisplayName = agent.label(),
                        round = round,
                        content = "Turn dropped due to network/provider error: $message",
                        isError = true,
                    )
                    transcript += msg
                    onMessage(msg)
                    return Pair(null, message)
                }

                // Anti-Looping Inspection (TASK-02)
                if (antiLoop.enabled && candidateTurns.isNotEmpty()) {
                    val audit = LoopDetector.inspect(
                        newText = reply.content,
                        priorTurns = candidateTurns,
                        threshold = antiLoop.maxSimilarityThreshold,
                        maxContiguous = antiLoop.maxContiguousDuplicateChars
                    )

                    if (audit is LoopInspectionResult.LoopDetected) {
                        attempts++
                        if (attempts <= antiLoop.maxRetries) {
                            tempBump += antiLoop.retryTemperatureBump
                            freqBump += 0.60
                            continue
                        } else {
                            // Fallback Circuit Breaker
                            val fallbackMsg = when (antiLoop.fallbackAction) {
                                LoopAction.CONVERT_TO_CONCESSION -> {
                                    DebateMessage(
                                        seatId = agent.id,
                                        provider = agent.provider,
                                        authorDisplayName = agent.label(),
                                        round = round,
                                        content = "### ${agent.label()} — Round $round\n*Position Maintained:* Participant maintains their prior position regarding the discussion topic with no new counter-arguments to add to the council.",
                                        isStalledConcession = true,
                                        tokensIn = reply.tokensIn,
                                        tokensOut = reply.tokensOut,
                                        tokensCached = reply.tokensCached,
                                    )
                                }
                                LoopAction.MODERATOR_INTERVENE -> {
                                    DebateMessage(
                                        seatId = "moderator",
                                        provider = config.primary.provider,
                                        authorDisplayName = "Moderator",
                                        round = round,
                                        content = "The deliberation between participants has reached an impasse on repetitive arguments. Let us pivot to examining the unaddressed trade-offs and concrete implementation constraints.",
                                        isModeratorIntervention = true,
                                    )
                                }
                                LoopAction.HALT_OR_ADVANCE -> {
                                    null
                                }
                                LoopAction.RETRY_WITH_DIRECTIVE -> {
                                    DebateMessage(
                                        seatId = agent.id,
                                        provider = agent.provider,
                                        authorDisplayName = agent.label(),
                                        round = round,
                                        content = "### ${agent.label()} — Round $round\n*Position Maintained:* Core position maintained.",
                                        isStalledConcession = true,
                                    )
                                }
                            }

                            if (fallbackMsg != null) {
                                transcript += fallbackMsg
                                onMessage(fallbackMsg)
                            }
                            return Pair(fallbackMsg, null)
                        }
                    }
                }

                val msg = DebateMessage(
                    seatId = agent.id,
                    provider = agent.provider,
                    authorDisplayName = agent.label(),
                    round = round,
                    content = reply.content,
                    tokensIn = reply.tokensIn,
                    tokensOut = reply.tokensOut,
                    tokensCached = reply.tokensCached,
                    isLoopRecovered = attempts > 0
                )
                transcript += msg
                onMessage(msg)
                return Pair(msg, null)
            }

            return Pair(null, null)
        }

        suspend fun handleTurnFailureGracefully(
            failedAgent: Agent,
            round: Int,
            errorMessage: String
        ): DebateResult {
            val round1Turns = transcript.count { it.round == 1 && !it.isError && !it.isSystem && !it.isUserComment }
            if (round <= 1 || round1Turns < config.agents.size) {
                return DebateResult(transcript, conclusion = null, error = errorMessage)
            }

            val emergencyWrapUpPrompt = """
            You are the Deliberation Moderator. A late participant turn was interrupted by a connection error.
            Synthesize a definitive final conclusion and outcome answering the core topic based on the extensive arguments already established by the council:
            
            ### 🎯 Final Outcome & Recommendation
            State the definitive decision answering the topic in 1-2 clear sentences.
            
            ### 💡 Core Established Consensus
            - 2-3 bullet points on the strongest points agreed across the completed rounds.
            
            ### ⚖️ Remaining Open Trade-offs
            - Key remaining points of contention.
            
            Note: Note that the discussion concluded gracefully based on ${round - 1} completed deliberation rounds.
            """.trimIndent()

            val moderatorAgent = config.moderation.moderatorProvider?.let { p ->
                config.agents.find { it.provider == p }
            } ?: config.primary

            return try {
                val conclusionReply = runnerFor(moderatorAgent).respondWithRetry(
                    agent = moderatorAgent.copy(systemPrompt = emergencyWrapUpPrompt),
                    topic = config.topic,
                    commonContext = config.commonContext,
                    commonInstructions = "",
                    transcript = contextView(moderatorAgent),
                )
                DebateResult(
                    transcript = transcript,
                    conclusion = conclusionReply.content,
                    warning = "Discussion concluded with emergency synthesis due to provider timeout in Round $round: $errorMessage"
                )
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                DebateResult(transcript, conclusion = null, error = errorMessage)
            }
        }

        fun buildModeratorPrompt(
            triggerReason: String,
            persona: com.dialex.model.ModeratorPersona,
            topic: String,
            currentRound: Int
        ): String {
            val personaDirective = when (persona) {
                com.dialex.model.ModeratorPersona.DELIBERATION_CHAIR ->
                    "You are the Deliberation Chair. Maintain parliamentary order, enforce topical adherence, and structure the council's inquiry."
                com.dialex.model.ModeratorPersona.EXECUTIVE_ARBITER ->
                    "You are the Executive Arbiter. You are pragmatic, ROI-driven, and intolerant of academic fluff or tangential theories. Demand concrete organizational reality."
                com.dialex.model.ModeratorPersona.SOCRATIC_PROBE ->
                    "You are the Socratic Inquirer. Probe unexamined assumptions, demand definitions of ambiguous terms, and challenge participants to test boundary conditions."
                com.dialex.model.ModeratorPersona.DEVILS_ADVOCATE_CHAIR ->
                    "You are the Devil's Advocate Chair. Aggressively challenge emerging consensus, point out blind spots, and demand rigorous stress-testing of assumptions."
            }

            return when (triggerReason) {
                "TOPICAL_DRIFT" -> """
                    $personaDirective
                    
                    [TRIGGER: TOPICAL DRIFT & PEDANTRY DETECTED]
                    The council was convened to address: "$topic".
                    Recent participant turns have drifted into abstract tangents, analogies, or pedantic theoretical side-quests.
                    
                    Issue an authoritative intervention for Round $currentRound:
                    1. Call order in the council and state explicitly what off-topic tangent must stop immediately.
                    2. Re-anchor the deliberation firmly onto the original topic: "$topic".
                    3. Provide a MANDATORY FOCUS QUESTION that participants must answer in their next turn.
                    
                    Format:
                    ### 🏛️ Deliberation Chair — Intervention (Round $currentRound)
                    **Order in the council.** [Explanation of drift]
                    
                    **Mandatory Steerage for Next Turn:**
                    [Concrete, high-signal questions strictly addressing "$topic"]
                """.trimIndent()

                "PERIODIC_CHECKPOINT" -> """
                    $personaDirective
                    
                    [TRIGGER: PERIODIC COUNCIL CHECKPOINT]
                    The council has concluded Round $currentRound on: "$topic".
                    Synthesize an interim state of the deliberation:
                    
                    Format:
                    ### 🏛️ Deliberation Chair — Interim Checkpoint (End of Round $currentRound)
                    **Current State of Consensus:**
                    • **ESTABLISHED:** [1-2 bullets on what participants have fundamentally settled]
                    • **CONTESTED:** [1-2 bullets on the core unresolved sticking points]
                    
                    **Mandatory Focus for Next Round:**
                    [Targeted question forcing participants to directly resolve the contested points]
                """.trimIndent()

                else -> """
                    $personaDirective
                    Deliver an authoritative steerage intervention to refocus the debate on "$topic".
                """.trimIndent()
            }
        }

        suspend fun checkModeratorIntervention(
            currentRound: Int,
            lastTurn: DebateMessage
        ): DebateMessage? {
            val modConfig = config.moderation
            if (!modConfig.enabled || modConfig.style == com.dialex.model.ModerationStyle.PASSIVE_WRAPUP_ONLY) return null
            if (lastTurn.isError || lastTurn.isSystem || lastTurn.isModeratorIntervention || lastTurn.isUserComment) return null

            var triggerReason: String? = null

            // Check Semantic Drift (TASK-06)
            if (modConfig.style == com.dialex.model.ModerationStyle.DYNAMIC_ACTIVE_STEERAGE ||
                modConfig.style == com.dialex.model.ModerationStyle.STRICT_ARBITRATION
            ) {
                val similarity = SemanticEvaluator.similarity(lastTurn.content, config.topic)
                if (similarity < modConfig.driftThreshold) {
                    triggerReason = "TOPICAL_DRIFT"
                }
            }

            if (triggerReason == null) return null

            val moderatorAgent = config.moderation.moderatorProvider?.let { p ->
                config.agents.find { it.provider == p }
            } ?: config.primary

            val steeragePrompt = buildModeratorPrompt(triggerReason, modConfig.persona, config.topic, currentRound)

            return try {
                val reply = runnerFor(moderatorAgent).respondWithRetry(
                    agent = moderatorAgent.copy(systemPrompt = steeragePrompt),
                    topic = config.topic,
                    commonContext = config.commonContext,
                    commonInstructions = "",
                    transcript = contextView(moderatorAgent),
                    modelOverride = modConfig.moderatorModel
                )

                DebateMessage(
                    seatId = "moderator",
                    provider = moderatorAgent.provider,
                    authorDisplayName = when (modConfig.persona) {
                        com.dialex.model.ModeratorPersona.DELIBERATION_CHAIR -> "Deliberation Chair"
                        com.dialex.model.ModeratorPersona.EXECUTIVE_ARBITER -> "Executive Arbiter"
                        com.dialex.model.ModeratorPersona.SOCRATIC_PROBE -> "Socratic Inquirer"
                        com.dialex.model.ModeratorPersona.DEVILS_ADVOCATE_CHAIR -> "Devil's Advocate Chair"
                    },
                    round = currentRound,
                    content = reply.content,
                    isModeratorIntervention = true,
                    tokensIn = reply.tokensIn,
                    tokensOut = reply.tokensOut,
                    tokensCached = reply.tokensCached,
                )
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                null
            }
        }

        suspend fun checkPeriodicCheckpoint(currentRound: Int): DebateMessage? {
            val modConfig = config.moderation
            if (!modConfig.enabled) return null
            if (modConfig.style != com.dialex.model.ModerationStyle.PERIODIC_CHECKPOINT &&
                modConfig.style != com.dialex.model.ModerationStyle.STRICT_ARBITRATION
            ) return null
            if (currentRound % modConfig.checkpointFrequencyRounds != 0) return null

            val moderatorAgent = config.moderation.moderatorProvider?.let { p ->
                config.agents.find { it.provider == p }
            } ?: config.primary

            val steeragePrompt = buildModeratorPrompt("PERIODIC_CHECKPOINT", modConfig.persona, config.topic, currentRound)

            return try {
                val reply = runnerFor(moderatorAgent).respondWithRetry(
                    agent = moderatorAgent.copy(systemPrompt = steeragePrompt),
                    topic = config.topic,
                    commonContext = config.commonContext,
                    commonInstructions = "",
                    transcript = contextView(moderatorAgent),
                    modelOverride = modConfig.moderatorModel
                )

                DebateMessage(
                    seatId = "moderator",
                    provider = moderatorAgent.provider,
                    authorDisplayName = when (modConfig.persona) {
                        com.dialex.model.ModeratorPersona.DELIBERATION_CHAIR -> "Deliberation Chair"
                        com.dialex.model.ModeratorPersona.EXECUTIVE_ARBITER -> "Executive Arbiter"
                        com.dialex.model.ModeratorPersona.SOCRATIC_PROBE -> "Socratic Inquirer"
                        com.dialex.model.ModeratorPersona.DEVILS_ADVOCATE_CHAIR -> "Devil's Advocate Chair"
                    },
                    round = currentRound,
                    content = reply.content,
                    isModeratorIntervention = true,
                    tokensIn = reply.tokensIn,
                    tokensOut = reply.tokensOut,
                    tokensCached = reply.tokensCached,
                )
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                null
            }
        }

        val agentCount = config.agents.size
        val agentTurns = transcript.filter { !it.isUserComment && !it.isError }
        var round = 1
        var resumeIndex = 0
        if (agentTurns.isNotEmpty()) {
            val maxRound = agentTurns.maxOf { it.round }
            round = maxRound
            val lastRoundAgentTurns = agentTurns.count { it.round == round }
            resumeIndex = lastRoundAgentTurns % agentCount
            if (resumeIndex == 0 && lastRoundAgentTurns > 0) {
                round++
                resumeIndex = 0
            }
        }
        var agreedEarly = false

        val bounded = config.roundMode == RoundMode.FIXED
        while (!bounded || round <= config.maxRounds) {
            for (agent in config.agents.drop(resumeIndex)) {
                if (isStopped()) return DebateResult(transcript, conclusion = null, paused = true)
                maybeCompact(round)
                val (msg, error) = speak(agent, round)
                if (error != null) return handleTurnFailureGracefully(agent, round, error)

                if (msg != null && !msg.isError && !msg.isSystem) {
                    val intervention = checkModeratorIntervention(round, msg)
                    if (intervention != null) {
                        transcript += intervention
                        onMessage(intervention)
                        if (config.moderation.enforceSteerageDirectives) {
                            activeSteerageDirective = intervention.content
                        }
                    }
                }

                // Budget Ceilings Handling (TASK-03 Section 4.5)
                val totalTokens = transcript.sumOf { (it.tokensIn ?: 0) + (it.tokensOut ?: 0) }
                val totalSpend = PricingTable.calculateDiscussionSpend(transcript, config.agents)

                val hitTokenBudget = (useCostEff && costEff.runTokenBudget > 0 && totalTokens >= costEff.runTokenBudget)
                val hitDollarBudget = (useCostEff && costEff.maxDollarSpendBudget > 0.0 && totalSpend >= costEff.maxDollarSpendBudget)
                val hitLegacyBudget = (tokenBudget > 0 && totalTokens >= tokenBudget && config.tokenBudgetAction == TokenBudgetAction.HARD_STOP && !useCostEff)

                if (hitLegacyBudget) {
                    val budgetMsg = "Token budget of $tokenBudget reached ($totalTokens used so far). " +
                        "Raise it in Settings > Limits, then click Resume to continue."
                    val errTurn = DebateMessage(
                        seatId = agent.id,
                        provider = agent.provider,
                        authorDisplayName = agent.label(),
                        round = round,
                        content = budgetMsg,
                        isError = true
                    )
                    transcript += errTurn
                    onMessage(errTurn)
                    return DebateResult(
                        transcript,
                        conclusion = null,
                        error = budgetMsg,
                        earlyExitReason = "TOKEN_BUDGET"
                    )
                }

                if (hitTokenBudget || hitDollarBudget) {
                    val budgetReason = if (hitDollarBudget) {
                        "Dollar spend ceiling of $${costEff.maxDollarSpendBudget} reached ($${totalSpend} spent)"
                    } else {
                        "Token budget ceiling of ${costEff.runTokenBudget} tokens reached ($totalTokens used)"
                    }

                    val budgetMarker = DebateMessage(
                        seatId = "system",
                        provider = config.primary.provider,
                        authorDisplayName = "System",
                        round = round,
                        content = "$budgetReason. Synthesizing final decisive outcome under budget ceiling.",
                        isSystem = true
                    )
                    transcript += budgetMarker
                    onMessage(budgetMarker)

                    val moderatorAgent = config.moderation.moderatorProvider?.let { p ->
                        config.agents.find { it.provider == p }
                    } ?: config.primary

                    val budgetWrapUp = """
                    You are the Deliberation Moderator. $budgetReason.
                    Synthesize a decisive final conclusion and outcome answering the topic based on the arguments established so far:

                    ### 🎯 Final Outcome & Recommendation
                    State the definitive decision or recommendation answering the topic in 1-2 clear, decisive sentences.

                    ### 💡 Core Established Consensus
                    - 2-3 concise bullet points on the strongest deciding arguments.

                    ### ⚖️ Remaining Open Trade-offs
                    - Key remaining trade-offs.

                    Note: Concluded cleanly under allocated budget constraint.
                    """.trimIndent()

                    val conclusionNorm = SamplingNormalizer.normalize(
                        config = config.sampling,
                        provider = moderatorAgent.provider,
                        round = round,
                        maxRounds = if (config.roundMode == RoundMode.FIXED) config.maxRounds else 10,
                        isConclusion = true
                    )

                    return try {
                        val conclusion = runnerFor(moderatorAgent).respondWithRetry(
                            agent = moderatorAgent.copy(
                                systemPrompt = budgetWrapUp,
                                temperature = conclusionNorm.temperature,
                                topP = conclusionNorm.topP
                            ),
                            topic = config.topic,
                            commonContext = config.commonContext,
                            commonInstructions = "",
                            transcript = contextView(moderatorAgent),
                        )
                        DebateResult(
                            transcript = transcript,
                            conclusion = conclusion.content,
                            isConsensusReached = false,
                            earlyExitReason = "BUDGET_CEILING_REACHED"
                        )
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        DebateResult(
                            transcript = transcript,
                            conclusion = "The discussion concluded upon reaching budget ceiling.",
                            isConsensusReached = false,
                            earlyExitReason = "BUDGET_CEILING_REACHED"
                        )
                    }
                }

                // Mid-round consensus check
                if (config.consensus.allowMidRoundTermination) {
                    val eval = ConsensusDetector.evaluateConsensus(round, transcript, config)
                    if (eval is ConsensusEvaluationResult.Achieved) {
                        agreedEarly = true
                        val percent = (eval.ratio * 100).toInt()
                        val marker = DebateMessage(
                            seatId = "system",
                            provider = config.primary.provider,
                            authorDisplayName = "System",
                            round = round,
                            content = "Consensus achieved at Round $round ($percent% agreement). Early stopping triggered.",
                            isSystem = true
                        )
                        transcript += marker
                        onMessage(marker)
                        break
                    }
                }
            }
            if (agreedEarly) break

            resumeIndex = 0
            if (!config.consensus.allowMidRoundTermination) {
                val eval = ConsensusDetector.evaluateConsensus(round, transcript, config)
                if (eval is ConsensusEvaluationResult.Achieved) {
                    agreedEarly = true
                    val percent = (eval.ratio * 100).toInt()
                    val marker = DebateMessage(
                        seatId = "system",
                        provider = config.primary.provider,
                        authorDisplayName = "System",
                        round = round,
                        content = "Consensus achieved at Round $round ($percent% agreement). Early stopping triggered.",
                        isSystem = true
                    )
                    transcript += marker
                    onMessage(marker)
                    break
                }
            }
            val checkpoint = checkPeriodicCheckpoint(round)
            if (checkpoint != null) {
                transcript += checkpoint
                onMessage(checkpoint)
                if (config.moderation.enforceSteerageDirectives) {
                    activeSteerageDirective = checkpoint.content
                }
            }
            round++
        }

        // Conclude using designated moderator agent
        val moderatorAgent = config.moderation.moderatorProvider?.let { p ->
            config.agents.find { it.provider == p }
        } ?: config.primary

        val baseWrapUp = when (config.depth.mode) {
            com.dialex.model.DepthMode.CASUAL -> """
                You are the Deliberation Moderator. Deliberation concluded on: "${config.topic}".
                Synthesize a casual, punchy, accessible conclusion for everyday users (strictly NO LaTeX, NO formulas, NO academic jargon):

                ### 🎯 Direct Answer
                State the direct answer/conclusion in 1–2 clear, punchy sentences.

                ### 👍 What Works (Pros)
                - 2–3 simple, relatable bullet points with real-world analogies.

                ### ⚠️ What to Watch Out For (Cons)
                - 1–2 practical bullet points on drawbacks or warnings.

                ### 💡 Final Tip
                1 sentence of direct, practical advice.
            """.trimIndent()
            com.dialex.model.DepthMode.EXECUTIVE -> """
                You are the Deliberation Moderator. Deliberation concluded on: "${config.topic}".
                Synthesize an executive decision brief for business and engineering leaders:

                ### 🎯 Strategic Decision & Recommendation
                State the definitive decision or recommendation answering the topic in 1–2 authoritative, decisive sentences.

                ### 📊 Strategic Trade-off Matrix
                | Option / Dimension | Strategic Benefit | Operational Risk | Headcount / Cost Impact |
                | --- | --- | --- | --- |
                | Direct Automation | High speed & unit cost reduction | Semantic drift & edge case hallucination | Reduced junior operational headcount |
                | Human-in-the-Loop | High governance & accountability | Higher operational latency | Maintained senior strategic leadership |

                ### ⚠️ Risk & Governance Actions
                - 2–3 immediate, actionable operational steps for leadership.
            """.trimIndent()
            com.dialex.model.DepthMode.ACADEMIC -> """
                You are the Deliberation Moderator. Deliberation concluded on: "${config.topic}".
                Synthesize a formal academic invariant synthesis from first principles:

                ### 🎯 Theoretical Invariant Synthesis
                Derive the formal theoretical consensus from foundational axioms and invariants.

                ### 📐 Phase Boundary Framework
                Articulate governing boundary conditions and analytical limits, utilizing formal notations where appropriate.

                ### 🔬 Open Research Limits & Epistemic Bounds
                - 2–3 open foundational questions and empirical verification frontiers.
            """.trimIndent()
            com.dialex.model.DepthMode.CUSTOM -> if (agreedEarly) {
                "You are the Deliberation Moderator. The participants have reached unanimous consensus on the topic.\n" +
                    "Synthesize a brief, crisp consensus summary answering the core topic/question in discussion directly:\n\n" +
                    "### 🎯 Outcome\n" +
                    "State the direct answer/conclusion to the topic/question in 1–2 clear, decisive sentences (what the user needs to know).\n\n" +
                    "### 🤝 Key Consensus Points\n" +
                    "- 2–3 concise bullet points outlining the core arguments and agreement reached.\n\n" +
                    "### ⚠️ Key Caveats / Trade-offs\n" +
                    "- 1–2 bullet points on critical constraints or risks agreed upon (if any).\n\n" +
                    "Eliminate all conversational fluff, preamble, and filler words. Keep it ultra-crisp, high-signal, and actionable."
            } else {
                "You are the Deliberation Moderator. The deliberation rounds have completed.\n" +
                    "Synthesize a brief, crisp final outcome summary answering the topic/question:\n\n" +
                    "### 🎯 Final Outcome & Recommendation\n" +
                    "State the definitive decision or recommendation answering the topic/question in 1–2 clear, decisive sentences.\n\n" +
                    "### 💡 Core Rationale\n" +
                    "- 2–3 concise bullet points on the strongest deciding arguments.\n\n" +
                    "### ⚖️ Trade-offs & Next Steps\n" +
                    "- Key remaining trade-offs or recommended next actions.\n\n" +
                    "Eliminate all conversational fluff, preamble, and filler words. Keep it ultra-crisp, high-signal, and actionable."
            }
        }
        val wrapUp = baseWrapUp

        val conclusionNorm = SamplingNormalizer.normalize(
            config = config.sampling,
            provider = moderatorAgent.provider,
            round = round,
            maxRounds = if (config.roundMode == RoundMode.FIXED) config.maxRounds else 10,
            isConclusion = true
        )

        return try {
            val conclusion = runnerFor(moderatorAgent).respondWithRetry(
                agent = moderatorAgent.copy(
                    systemPrompt = wrapUp,
                    temperature = conclusionNorm.temperature,
                    topP = conclusionNorm.topP
                ),
                topic = config.topic,
                commonContext = config.commonContext,
                commonInstructions = instructions,
                transcript = contextView(moderatorAgent),
            )
            DebateResult(
                transcript = transcript,
                conclusion = conclusion.content,
                isConsensusReached = agreedEarly,
                earlyExitReason = if (agreedEarly) "CONSENSUS" else null
            )
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            val message = t.message ?: t.toString()
            val msg = DebateMessage(
                seatId = moderatorAgent.id,
                provider = moderatorAgent.provider,
                authorDisplayName = moderatorAgent.label(),
                round = round,
                content = message,
                isError = true
            )
            transcript += msg
            onMessage(msg)
            DebateResult(
                transcript = transcript,
                conclusion = null,
                error = message,
                isConsensusReached = agreedEarly,
                earlyExitReason = if (agreedEarly) "CONSENSUS" else null
            )
        }
    }
}
