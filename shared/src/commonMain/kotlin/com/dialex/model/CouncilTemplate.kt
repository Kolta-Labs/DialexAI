package com.dialex.model

import kotlinx.serialization.Serializable

/**
 * Pre-configured council archetypes for high-stakes executive and technical decision-making.
 * Applies specialized personas, models, prompts, deliverable formats, and moderation policies in 1-click.
 */
@Serializable
data class CouncilTemplate(
    val id: String,
    val title: String,
    val subtitle: String,
    val suggestedTopic: String = "",
    val commonContext: String = "",
    val deliverableFormat: DeliverableFormat = DeliverableFormat.EXECUTIVE_BRIEF,
    val moderationStrictness: Int = 3,
    val primaryAgent: Agent,
    val secondaryAgent: Agent? = null,
    val tertiaryAgent: Agent? = null,
    val quaternaryAgent: Agent? = null,
    val quinaryAgent: Agent? = null,
    val senaryAgent: Agent? = null,
    val badgeLabel: String = "Council",
    val isCustom: Boolean = false,
    val createdAt: Long = 0L
) {
    fun toDebateConfig(baseConfig: DebateConfig): DebateConfig {
        return baseConfig.copy(
            topic = if (baseConfig.topic.isBlank()) suggestedTopic else baseConfig.topic,
            commonContext = if (baseConfig.commonContext.isBlank()) commonContext else baseConfig.commonContext,
            primary = primaryAgent,
            secondary = secondaryAgent,
            tertiary = tertiaryAgent,
            quaternary = quaternaryAgent,
            quinary = quinaryAgent,
            senary = senaryAgent,
            deliverable = baseConfig.deliverable.copy(format = deliverableFormat),
            moderation = baseConfig.moderation.copy(
                enabled = true,
                strictness = moderationStrictness
            )
        )
    }

    companion object {
        fun fromDebateConfig(
            id: String,
            title: String,
            subtitle: String,
            badgeLabel: String,
            config: DebateConfig
        ): CouncilTemplate {
            return CouncilTemplate(
                id = id,
                title = title,
                subtitle = subtitle,
                suggestedTopic = config.topic,
                commonContext = config.commonContext,
                deliverableFormat = config.deliverable.format,
                moderationStrictness = config.moderation.strictness,
                primaryAgent = config.primary,
                secondaryAgent = config.secondary,
                tertiaryAgent = config.tertiary,
                quaternaryAgent = config.quaternary,
                quinaryAgent = config.quinary,
                senaryAgent = config.senary,
                badgeLabel = badgeLabel.ifBlank { "Custom" },
                isCustom = true,
                createdAt = System.currentTimeMillis()
            )
        }
    }
}
