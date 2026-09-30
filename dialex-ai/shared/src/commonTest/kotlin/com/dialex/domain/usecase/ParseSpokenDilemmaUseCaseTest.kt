package com.dialex.domain.usecase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ParseSpokenDilemmaUseCaseTest {

    @Test
    fun parses_tech_dilemma_and_extracts_constraints() {
        val useCase = ParseSpokenDilemmaUseCase()
        val speech = "Should we migrate our backend to Go; budget is $30k and deadline is 2 months"

        val draft = useCase(speech)

        assertEquals("Should we migrate our backend to Go", draft.topic)
        assertEquals("budget is $30k and deadline is 2 months", draft.constraints)
        assertEquals("tech_architecture", draft.suggestedPresetId)
    }

    @Test
    fun parses_deal_negotiation_dilemma() {
        val useCase = ParseSpokenDilemmaUseCase()
        val speech = "Prepare strategy for our enterprise software contract negotiation"

        val draft = useCase(speech)

        assertEquals("Prepare strategy for our enterprise software contract negotiation", draft.topic)
        assertEquals("deal_negotiation", draft.suggestedPresetId)
    }
}
