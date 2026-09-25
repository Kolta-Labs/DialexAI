package com.dialex.presentation.setup

import com.dialex.domain.model.DiscussionMode
import com.dialex.domain.model.LedgerItemType
import com.dialex.domain.model.SocraticConfig
import com.dialex.domain.model.SocraticDigest
import com.dialex.domain.model.SocraticLedgerItem
import com.dialex.domain.model.SocraticStage
import com.dialex.domain.model.SocraticStance
import com.dialex.model.Agent
import com.dialex.model.DebateConfig
import com.dialex.model.Discussion
import com.dialex.model.Provider
import com.dialex.model.defaultModel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SocraticInterviewSetupTest {

    private fun sampleConfig(topic: String = "High-Throughput Microservice Architecture"): DebateConfig {
        return DebateConfig(
            topic = topic,
            primary = Agent(
                provider = Provider.ANTHROPIC,
                model = Provider.ANTHROPIC.defaultModel(),
                displayName = "Socratic Interrogator"
            )
        )
    }

    @Test
    fun testSocraticDiscussionCreationAndValidation() {
        val config = sampleConfig()
        val socraticConf = SocraticConfig(
            stance = SocraticStance.RUTHLESS_ELENCHUS,
            interviewerPersonaId = "devils_advocate",
            interviewerName = "Ruthless Opponent"
        )
        val discussion = Discussion(
            id = "socratic-test-1",
            projectId = "test-proj",
            name = "Microservice Architecture Thesis",
            mode = DiscussionMode.SOCRATIC_INTERVIEW,
            config = config,
            socraticConfig = socraticConf
        )

        assertEquals(DiscussionMode.SOCRATIC_INTERVIEW, discussion.mode)
        assertNotNull(discussion.socraticConfig)
        assertEquals(SocraticStance.RUTHLESS_ELENCHUS, discussion.socraticConfig?.stance)
        assertEquals("Ruthless Opponent", discussion.socraticConfig?.interviewerName)
        assertEquals(1, discussion.config.agents.size)
    }

    @Test
    fun testSocraticStanceAttributes() {
        assertEquals("Classic Elenchus", SocraticStance.RUTHLESS_ELENCHUS.displayName)
        assertEquals("Maieutic Architecture", SocraticStance.MAIEUTIC_ARCHITECT.displayName)
        assertEquals("Radical First Principles", SocraticStance.FIRST_PRINCIPLES.displayName)
        assertEquals("Adversarial Red-Team", SocraticStance.ADVERSARIAL_RED_TEAM.displayName)
        assertEquals("Aporia & Extreme Scale", SocraticStance.APORIA_BOUNDARY_PUSHER.displayName)

        // Ensure 5 standard stages
        assertEquals(5, SocraticStage.entries.size)
        assertEquals(1, SocraticStage.HYPOTHESIS_EXTRACTION.stepNumber)
        assertEquals(5, SocraticStage.MAIEUTIC_HARDENING.stepNumber)
    }

    @Test
    fun testEpistemicLedgerAndDigestState() {
        val ledgerItems = listOf(
            SocraticLedgerItem(
                id = "led-1",
                type = LedgerItemType.HARDENED,
                statement = "Eventual consistency SLA is ≤ 500ms p99",
                turn = 1
            ),
            SocraticLedgerItem(
                id = "led-2",
                type = LedgerItemType.CONCEDED,
                statement = "Synchronous RPC calls between clusters cause cascading partition failure",
                turn = 2
            ),
            SocraticLedgerItem(
                id = "led-3",
                type = LedgerItemType.UNDER_SIEGE,
                statement = "Raft consensus handles WAN partitions without split-brain risk",
                turn = 3
            )
        ).toImmutableList()

        val digest = SocraticDigest(
            initialHypothesis = "Microservice architecture can maintain ACID across geographic regions",
            defendedInvariants = listOf("Bounded 500ms queue depth", "No synchronous cross-region RPC"),
            exposedBlindSpots = listOf("ACID guarantees cannot be maintained across WAN partitions without blocking latency"),
            hardenedThesis = "Microservices require eventual consistency with bounded async queues across regions",
            residualTensions = listOf("Replication lag during network partitions vs read staleness")
        )

        assertEquals(3, ledgerItems.size)
        assertEquals(1, ledgerItems.count { it.type == LedgerItemType.HARDENED })
        assertEquals(1, ledgerItems.count { it.type == LedgerItemType.CONCEDED })
        assertEquals(1, ledgerItems.count { it.type == LedgerItemType.UNDER_SIEGE })

        assertEquals(2, digest.defendedInvariants.size)
        assertEquals(1, digest.exposedBlindSpots.size)
        assertEquals(1, digest.residualTensions.size)
        assertTrue(digest.hardenedThesis.contains("eventual consistency"))
    }
}
