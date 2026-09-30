package com.dialex.presentation.chat

import com.dialex.model.DiscussionStatus
import com.dialex.domain.model.ElevateResult
import com.dialex.domain.model.SocraticDigest
import com.dialex.domain.model.SocraticElevateRequest
import com.dialex.domain.model.SocraticStage
import com.dialex.domain.model.SocraticStance
import com.dialex.domain.model.SocraticTurnRequest
import com.dialex.domain.model.SocraticTurnResponse
import com.dialex.domain.usecase.ConductSocraticTurnUseCase
import com.dialex.domain.usecase.ElevateSocraticToCouncilUseCase
import com.dialex.domain.usecase.GenerateSocraticDigestUseCase
import com.dialex.domain.usecase.UpdateDiscussionUseCase
import com.dialex.model.DebateMessage
import com.dialex.model.Discussion
import com.dialex.model.Provider

data class SocraticTurnResult(
    val updatedDiscussion: Discussion,
    val probeQuestion: String,
    val newStage: SocraticStage
)

/**
 * Encapsulates Socratic Interview state changes, turn generation, epistemic digests,
 * and escalation to full council discussions.
 */
class SocraticSessionDelegate(
    private val conductSocraticTurnUseCase: ConductSocraticTurnUseCase? = null,
    private val generateSocraticDigestUseCase: GenerateSocraticDigestUseCase? = null,
    private val elevateSocraticToCouncilUseCase: ElevateSocraticToCouncilUseCase? = null,
    private val updateDiscussionUseCase: UpdateDiscussionUseCase,
) {

    suspend fun handleUserTurn(
        discussionId: String,
        statement: String,
        currentDisc: Discussion,
        currentStage: SocraticStage?
    ): Result<SocraticTurnResult> = runCatching {
        val currentRound = (currentDisc.transcript.maxOfOrNull { it.round } ?: 0) + 1
        val userMsg = DebateMessage(
            seatId = "interlucotor",
            provider = Provider.CUSTOM,
            authorDisplayName = "You (Interlocutor)",
            round = currentRound,
            content = statement,
            isUserComment = true,
            timestampMs = System.currentTimeMillis()
        )
        val updatedDisc = currentDisc.copy(
            transcript = currentDisc.transcript + userMsg,
            status = DiscussionStatus.RUNNING
        )
        updateDiscussionUseCase(updatedDisc)

        val turnRequest = SocraticTurnRequest(
            message = statement,
            topic = currentDisc.config.topic.ifBlank { currentDisc.name },
            stance = currentDisc.socraticConfig?.stance ?: SocraticStance.RUTHLESS_ELENCHUS,
            stage = currentStage ?: currentDisc.socraticConfig?.stage ?: SocraticStage.HYPOTHESIS_EXTRACTION,
            context = currentDisc.config.commonContext,
            attachedFiles = currentDisc.attachedFiles
        )
        val useCase = checkNotNull(conductSocraticTurnUseCase) { "ConductSocraticTurnUseCase is required" }
        val response = useCase(discussionId, turnRequest)

        val examinerMsg = DebateMessage(
            seatId = "interviewer",
            provider = currentDisc.config.primary.provider,
            authorDisplayName = currentDisc.socraticConfig?.interviewerName?.ifBlank { "Socratic Examiner" } ?: "Socratic Examiner",
            round = currentRound + 1,
            content = response.probeQuestion,
            timestampMs = System.currentTimeMillis()
        )
        val withExaminer = updatedDisc.copy(
            transcript = updatedDisc.transcript + examinerMsg,
            socraticLedger = (updatedDisc.socraticLedger + response.ledgerUpdates).distinctBy { it.id },
            status = DiscussionStatus.PAUSED
        )
        val saved = updateDiscussionUseCase(withExaminer)
        SocraticTurnResult(saved, response.probeQuestion, response.newStage)
    }

    suspend fun generateDigest(
        discussionId: String,
        currentDisc: Discussion
    ): Result<Pair<Discussion, SocraticDigest>> = runCatching {
        val useCase = checkNotNull(generateSocraticDigestUseCase) { "GenerateSocraticDigestUseCase is required" }
        val digest = useCase(discussionId)
        val updated = currentDisc.copy(socraticDigest = digest, status = DiscussionStatus.DONE)
        val saved = updateDiscussionUseCase(updated)
        Pair(saved, digest)
    }

    suspend fun elevateToCouncil(
        discussionId: String,
        digest: SocraticDigest,
        targetProjectId: String
    ): Result<ElevateResult> = runCatching {
        val useCase = checkNotNull(elevateSocraticToCouncilUseCase) { "ElevateSocraticToCouncilUseCase is required" }
        val req = SocraticElevateRequest(
            projectId = targetProjectId,
            digest = digest,
            parentDiscussionId = discussionId
        )
        useCase(discussionId, req)
    }
}
