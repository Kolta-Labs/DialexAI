package com.dialex.presentation.setup

import com.dialex.model.*

/**
 * Super-common discussion templates (maximum 4).
 * Focused on everyday software, product, engineering, and decision deliberation.
 */
object CouncilTemplates {
    val all: List<CouncilTemplate> = listOf(
        CouncilTemplate(
            id = "arch_review",
            title = "Architecture & System Design",
            subtitle = "Scalability, reliability, and engineering tradeoffs",
            suggestedTopic = "Evaluate technical architecture, component boundaries, and performance tradeoffs for [System / Feature].",
            commonContext = "Audience: Engineering team and technical leads. Objective: Ensure high reliability, minimal accidental complexity, and scalable infrastructure.",
            deliverableFormat = DeliverableFormat.DECISION_MATRIX,
            moderationStrictness = 3,
            badgeLabel = "Engineering",
            primaryAgent = Agent(
                provider = Provider.ANTHROPIC,
                displayName = "Systems Architect",
                personaId = "se_system_architect",
                role = "Principal Systems Architect",
                systemPrompt = "",
                model = "claude-sonnet-5"
            ),
            secondaryAgent = Agent(
                provider = Provider.OPENAI,
                displayName = "Engineering Pragmatist",
                personaId = "se_minimalist_yagni",
                role = "Pragmatic Minimalist",
                systemPrompt = "",
                model = "gpt-5.6-sol"
            ),
            tertiaryAgent = Agent(
                provider = Provider.GEMINI,
                displayName = "Reliability & Security",
                personaId = "se_security_engineer",
                role = "AppSec / Security Architect",
                systemPrompt = "",
                model = "gemini-3.7-flash"
            )
        ),
        CouncilTemplate(
            id = "product_strategy",
            title = "Product Strategy & UX",
            subtitle = "Feature prioritization, user delight, and roadmap",
            suggestedTopic = "Deliberate on product strategy, user problem validation, and roadmap prioritization for [Product / Initiative].",
            commonContext = "Audience: Product managers, designers, and business stakeholders. Objective: Align on customer impact, viable scope, and user experience.",
            deliverableFormat = DeliverableFormat.ACTION_PLAN,
            moderationStrictness = 3,
            badgeLabel = "Product",
            primaryAgent = Agent(
                provider = Provider.ANTHROPIC,
                displayName = "Head of Product",
                personaId = "biz_product_owner",
                role = "Product Management Lead",
                systemPrompt = "",
                model = "claude-sonnet-5"
            ),
            secondaryAgent = Agent(
                provider = Provider.OPENAI,
                displayName = "UX & Customer Advocate",
                personaId = "biz_customer_advocate",
                role = "UX & Customer Champion",
                systemPrompt = "",
                model = "gpt-5.6-sol"
            ),
            tertiaryAgent = Agent(
                provider = Provider.GEMINI,
                displayName = "Delivery Strategist",
                personaId = "biz_growth_strategist",
                role = "GTM & Growth Strategist",
                systemPrompt = "",
                model = "gemini-3.7-flash"
            )
        ),
        CouncilTemplate(
            id = "code_security",
            title = "Code & Security Audit",
            subtitle = "Vulnerabilities, code health, and maintainability",
            suggestedTopic = "Perform a thorough architectural and security audit of [Codebase / API / Module].",
            commonContext = "Audience: Software engineers and security reviewers. Objective: Surface security vulnerabilities, anti-patterns, and technical debt.",
            deliverableFormat = DeliverableFormat.EXECUTIVE_BRIEF,
            moderationStrictness = 4,
            badgeLabel = "Security",
            primaryAgent = Agent(
                provider = Provider.ANTHROPIC,
                displayName = "Security Auditor",
                personaId = "se_security_engineer",
                role = "AppSec / Security Architect",
                systemPrompt = "",
                model = "claude-sonnet-5"
            ),
            secondaryAgent = Agent(
                provider = Provider.OPENAI,
                displayName = "Code Quality Lead",
                personaId = "se_clean_code_craftsman",
                role = "Staff Software Craftsman",
                systemPrompt = "",
                model = "gpt-5.6-sol"
            ),
            tertiaryAgent = Agent(
                provider = Provider.DEEPSEEK,
                displayName = "Performance Specialist",
                personaId = "se_performance_engineer",
                role = "Systems & Performance Architect",
                systemPrompt = "",
                model = "deepseek-chat"
            )
        ),
        CouncilTemplate(
            id = "brainstorm_debate",
            title = "Brainstorming & Critical Debate",
            subtitle = "Exploratory ideation vs devil's advocate testing",
            suggestedTopic = "Brainstorm creative approaches for [Challenge / Goal] and rigorously stress-test potential downsides.",
            commonContext = "Audience: Team leads and innovators. Objective: Expand solution possibilities, then stress-test assumptions with rigorous skepticism.",
            deliverableFormat = DeliverableFormat.PRO_CON_LIST,
            moderationStrictness = 3,
            badgeLabel = "Ideation",
            primaryAgent = Agent(
                provider = Provider.ANTHROPIC,
                displayName = "Creative Ideator",
                personaId = "sys_optimist",
                role = "Optimist & Possibility Expander",
                systemPrompt = "",
                model = "claude-sonnet-5"
            ),
            secondaryAgent = Agent(
                provider = Provider.OPENAI,
                displayName = "Critical Challenger",
                personaId = "sys_devils_advocate",
                role = "Devil's Advocate",
                systemPrompt = "",
                model = "gpt-5.6-sol"
            ),
            tertiaryAgent = Agent(
                provider = Provider.GEMINI,
                displayName = "Pragmatic Synthesizer",
                personaId = "sys_facilitator",
                role = "Facilitator & Synthesizer",
                systemPrompt = "",
                model = "gemini-3.7-flash"
            )
        )
    )
}
