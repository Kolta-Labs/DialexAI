package com.dialex.domain.model

import com.dialex.model.Agent
import com.dialex.model.DebateConfig
import com.dialex.model.Provider
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.Serializable

/**
 * Pre-configured council archetypes for 1-tap mobile deliberation.
 */
@Serializable
data class CouncilPreset(
    val id: String,
    val title: String,
    val category: String,
    val subtitle: String,
    val description: String,
    val suggestedTopic: String,
    val commonContext: String,
    val primaryAgent: Agent,
    val secondaryAgents: List<Agent>,
    val badgeLabel: String
) {
    fun toDebateConfig(): DebateConfig {
        return DebateConfig(
            topic = suggestedTopic,
            commonContext = commonContext,
            primary = primaryAgent,
            secondary = secondaryAgents.getOrNull(0),
            tertiary = secondaryAgents.getOrNull(1),
            quaternary = secondaryAgents.getOrNull(2),
            quinary = secondaryAgents.getOrNull(3),
            userInterventionPolicy = com.dialex.model.UserInterventionPolicy.AUTONOMOUS_AUTOPILOT
        )
    }

    companion object {
        val ExecutiveRedTeam = CouncilPreset(
            id = "exec_red_team",
            title = "Executive Red Team",
            category = "Strategy",
            subtitle = "Operator vs Growth vs Risk Auditor",
            description = "Stress-test critical business decisions, term sheets, or pivot strategies against operational friction, growth velocity, and regulatory risk.",
            suggestedTopic = "Evaluate our strategic proposal to [Decision / Initiative]. Identify blindspots, failure modes, and trade-offs.",
            commonContext = "Audience: Executive Leadership and Board. Objective: Rigorously stress-test thesis and challenge assumptions before execution.",
            badgeLabel = "Executive",
            primaryAgent = Agent(
                provider = Provider.ANTHROPIC,
                displayName = "Chief Operating Officer",
                systemPrompt = "You are a seasoned COO and pragmatic operator. Scrutinize execution complexity, unit economics, team bandwidth, operational dependencies, and delivery risk.",
                model = "claude-sonnet-5"
            ),
            secondaryAgents = listOf(
                Agent(
                    provider = Provider.OPENAI,
                    displayName = "Growth Strategist",
                    systemPrompt = "You are an aggressive VP of Growth. Challenge slow execution, evaluate market capture, competitive moats, customer acquisition velocity, and upside potential.",
                    model = "gpt-5.6-sol"
                ),
                Agent(
                    provider = Provider.GEMINI,
                    displayName = "Risk & Compliance Auditor",
                    systemPrompt = "You are a Chief Risk Officer. Identify legal liabilities, reputational risks, cybersecurity implications, regulatory hurdles, and catastrophic tail risks.",
                    model = "gemini-3.7-flash"
                )
            )
        )

        val TechArchitecture = CouncilPreset(
            id = "tech_architecture",
            title = "Architecture & Systems",
            category = "Engineering",
            subtitle = "Systems Architect vs Pragmatist vs SRE",
            description = "Evaluate data flow, consistency models, microservice boundaries, blast radius containment, and maintainability.",
            suggestedTopic = "Evaluate technical architecture and engineering trade-offs for [System / Service].",
            commonContext = "Audience: Engineering Staff and Tech Leads. Objective: Avoid accidental complexity and ensure high reliability under scale.",
            badgeLabel = "Engineering",
            primaryAgent = Agent(
                provider = Provider.ANTHROPIC,
                displayName = "Systems Architect",
                systemPrompt = "You are a Principal Systems Architect. Evaluate data flow, consistency guarantees, fault tolerance, blast radius containment, and long-term maintainability.",
                model = "claude-sonnet-5"
            ),
            secondaryAgents = listOf(
                Agent(
                    provider = Provider.OPENAI,
                    displayName = "Engineering Pragmatist",
                    systemPrompt = "You are a pragmatic Senior Staff Engineer. Challenge premature optimization, advocate for shipping velocity, developer ergonomics, and operational simplicity.",
                    model = "gpt-5.6-sol"
                ),
                Agent(
                    provider = Provider.GEMINI,
                    displayName = "Reliability & Security SRE",
                    systemPrompt = "You are an SRE and Security Lead. Probe edge cases, attack vectors, data protection, p99 latency spikes, failure modes, and observability requirements.",
                    model = "gemini-3.7-flash"
                )
            )
        )

        val DevilsAdvocate = CouncilPreset(
            id = "devils_advocate",
            title = "Devil's Advocate",
            category = "Deliberation",
            subtitle = "Harsh Skeptic vs Visionary Optimist",
            description = "Force hyper-critical counter-arguments against an idea to expose wishful thinking, unvalidated hypotheses, and hidden assumptions.",
            suggestedTopic = "Subject [Idea / Proposal] to adversarial critique. Uncover why this might fail completely.",
            commonContext = "Audience: Decision makers seeking unvarnished truth. Objective: Challenge confirmation bias.",
            badgeLabel = "Adversarial",
            primaryAgent = Agent(
                provider = Provider.ANTHROPIC,
                displayName = "The Devil's Advocate",
                systemPrompt = "You are an unrelenting Devil's Advocate. Your job is to identify fatal flaws, disconfirming evidence, wishful thinking, cognitive biases, and unstated assumptions. Be polite but ruthlessly rigorous.",
                model = "claude-sonnet-5"
            ),
            secondaryAgents = listOf(
                Agent(
                    provider = Provider.OPENAI,
                    displayName = "Visionary Defender",
                    systemPrompt = "You are a visionary advocate for the idea. Defend the strategic merit, counter the skeptic's pessimism with viable mitigations, and explain the asymmetric upside.",
                    model = "gpt-5.6-sol"
                )
            )
        )

        val DealNegotiation = CouncilPreset(
            id = "deal_negotiation",
            title = "Deal & Negotiation Prep",
            category = "Negotiation",
            subtitle = "Hardball Counterparty vs Dealmaker",
            description = "Simulate tough negotiation dynamics, term sheet concessions, pricing resistance, and walk-away points before entering the room.",
            suggestedTopic = "Prepare negotiation strategy and counter-arguments for [Deal / Contract / Offer].",
            commonContext = "Audience: Deal lead or negotiator. Objective: Maximize value while preserving deal momentum.",
            badgeLabel = "Negotiation",
            primaryAgent = Agent(
                provider = Provider.ANTHROPIC,
                displayName = "Hardball Counterparty",
                systemPrompt = "You represent the counterparty in a tough commercial negotiation. Push back hard on pricing, demanding aggressive terms, questioning value propositions, and testing the user's resolve.",
                model = "claude-sonnet-5"
            ),
            secondaryAgents = listOf(
                Agent(
                    provider = Provider.OPENAI,
                    displayName = "Principled Dealmaker",
                    systemPrompt = "You are an expert Harvard-style principled negotiator. Advise on finding win-win BATNAs, reframing sticking points, and securing optimal terms without killing the relationship.",
                    model = "gpt-5.6-sol"
                )
            )
        )

        val defaultPresets: ImmutableList<CouncilPreset> = listOf(
            ExecutiveRedTeam,
            TechArchitecture,
            DevilsAdvocate,
            DealNegotiation
        ).toImmutableList()
    }
}
