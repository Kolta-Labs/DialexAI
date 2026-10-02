package com.dialex.model

/**
 * Councils for people with ONE API key: every seat is a different persona on the same
 * provider and model, tuned against the conformity that makes same-model debate agree too
 * easily (blind first round, anonymized peers, an anti-conformity rule in every persona).
 * Mirrors the Go engine's quickstart package; keep the two in step.
 */
enum class OneKeyMode(val title: String, val blurb: String, val rounds: Int, val keepDissent: Boolean) {
    PREMORTEM("Pre-mortem", "Assume the plan already failed. Find out why before you start.", 2, false),
    RED_TEAM("Red team", "A proponent and attackers go three rounds on your plan.", 3, false),
    TENTH_MAN("Tenth man", "When everyone agrees, one seat must argue the opposite. Dissent is kept.", 3, true),
}

object OneKeyCouncil {
    private const val ANTI_CONFORMITY = "\n\nRules for everyone: do not agree just to be agreeable or polite. Change your position only " +
        "for a new argument or evidence, and say exactly what changed your mind. Be concrete: name the " +
        "assumption, the number, or the failure mode. Keep each turn under 250 words."

    private const val CHAIR = "You chair this council. You are neutral: you do not take a side. Frame the question, then weigh the " +
        "arguments on their merits. Your final verdict must state: the decision or recommendation, the strongest argument " +
        "AGAINST it, what would change your mind, and which disagreements remain unresolved. Never hide dissent." + ANTI_CONFORMITY

    private data class Persona(val name: String, val role: String, val prompt: String)

    private fun council(mode: OneKeyMode): List<Persona> = when (mode) {
        OneKeyMode.PREMORTEM -> listOf(
            Persona("Failure Analyst", "Pre-mortem Analyst", "You write the story of how this failed. Pick the most likely causes, in order, with the early warning signs nobody noticed. Assume the plan was executed as described." + ANTI_CONFORMITY),
            Persona("Operator", "Execution Realist", "You run the work day to day. Attack timelines, dependencies, staffing and hidden costs. Say what will really take twice as long." + ANTI_CONFORMITY),
            Persona("Sponsor", "Plan Advocate", "You want this to work. Defend the plan's strongest logic, but concede a risk only when it is real, and say what you would do to mitigate it." + ANTI_CONFORMITY),
        )
        OneKeyMode.RED_TEAM -> listOf(
            Persona("Proponent", "Steelman", "You make the strongest honest case for the proposal. Answer attacks with evidence, not rhetoric, and admit the points you cannot answer." + ANTI_CONFORMITY),
            Persona("Red Team", "Adversary", "You attack the proposal: unstated assumptions, failure modes, perverse incentives, what a competitor or critic would say. Do not soften." + ANTI_CONFORMITY),
            Persona("Risk Auditor", "Downside Specialist", "You look only at downside: legal, security, reputational and irreversible risks, and the worst realistic case. Rate each by likelihood and cost." + ANTI_CONFORMITY),
        )
        OneKeyMode.TENTH_MAN -> listOf(
            Persona("Strategist", "Case Builder", "You build the best case for the decision and say what outcome you expect." + ANTI_CONFORMITY),
            Persona("Analyst", "Evidence Checker", "You test the claims made so far: what is asserted without evidence, what numbers are missing, what is the base rate." + ANTI_CONFORMITY),
            Persona("Tenth Man", "Mandatory Dissenter", "You are the Tenth Man. Whatever the others conclude, you argue the opposite as strongly and honestly as you can, and you never open a reply with AGREED. Each turn name the one fact that, if true, would prove you right, and what the others have not answered." + ANTI_CONFORMITY),
        )
    }

    private fun framing(mode: OneKeyMode): String = when (mode) {
        OneKeyMode.PREMORTEM -> "Pre-mortem. Assume it is 12 months from now and this has FAILED. Explain why, then say what to do about it now."
        OneKeyMode.RED_TEAM -> "Red-team this proposal. The proponent steelmans it; the others attack it."
        OneKeyMode.TENTH_MAN -> "Decide this, with a mandatory dissenter."
    }

    /** Turns [current] into a one-provider council, keeping the user's topic, files and other settings. */
    fun apply(current: DebateConfig, mode: OneKeyMode): DebateConfig {
        val provider = current.primary.provider
        val modelName = current.primary.model.ifBlank { provider.defaultModel() }
        fun seat(p: Persona, n: Int) = Agent(
            provider = provider,
            model = modelName,
            runMode = current.primary.runMode,
            displayName = p.name,
            role = p.role,
            systemPrompt = p.prompt,
            id = "qs_${mode.name.lowercase()}_$n",
        )
        val seats = council(mode).mapIndexed { i, p -> seat(p, i + 1) }
        return current.copy(
            primary = seat(Persona("Chair", "Neutral Chair", CHAIR), 0),
            secondary = seats.getOrNull(0),
            tertiary = seats.getOrNull(1),
            quaternary = seats.getOrNull(2),
            quinary = null,
            senary = null,
            roundMode = RoundMode.FIXED,
            maxRounds = mode.rounds,
            commonContext = listOf(current.commonContext.trim(), framing(mode)).filter { it.isNotEmpty() }.joinToString("\n\n"),
            independence = IndependenceConfig(blindFirstRound = true, anonymizeTranscript = true),
            consensus = if (mode.keepDissent) current.consensus.copy(mode = ConsensusMode.DISABLED) else current.consensus,
        )
    }
}
