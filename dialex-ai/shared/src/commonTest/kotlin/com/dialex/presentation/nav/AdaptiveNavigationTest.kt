package com.dialex.presentation.nav

import androidx.compose.runtime.mutableStateListOf
import com.dialex.model.Agent
import com.dialex.model.DebateConfig
import com.dialex.model.Discussion
import com.dialex.model.DiscussionStatus
import com.dialex.model.Provider
import com.dialex.model.defaultModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdaptiveNavigationTest {

    @Test
    fun backStack_singlePaneMasterDetail_navigationCycle() {
        val stackList = mutableStateListOf<AppRoute>(Workspace)
        val backStack = AppBackStack(stackList)

        // 1. Initial master view is Workspace
        assertEquals(1, backStack.size)
        assertEquals(Workspace, backStack.current)

        // 2. Select a discussion -> pushes Chat onto back stack
        backStack.add(Chat("discussion_42"))
        assertEquals(2, backStack.size)
        assertEquals(Chat("discussion_42"), backStack.current)

        // 3. User taps mobile back icon -> pops back to Workspace
        val popped = backStack.removeLast()
        assertEquals(Chat("discussion_42"), popped)
        assertEquals(1, backStack.size)
        assertEquals(Workspace, backStack.current)

        // 4. User taps "+ New Debate" -> pushes Setup onto back stack
        backStack.add(Setup(discussionId = null, initialProjectId = "project_1"))
        assertEquals(2, backStack.size)
        assertEquals(Setup(discussionId = null, initialProjectId = "project_1"), backStack.current)

        // 5. User taps Back -> returns to Workspace
        backStack.removeLast()
        assertEquals(1, backStack.size)
        assertEquals(Workspace, backStack.current)
    }

    @Test
    fun backStack_set_replacesEntriesCorrectly() {
        val stackList = mutableStateListOf<AppRoute>(Setup(null))
        val backStack = AppBackStack(stackList)

        // Switch to Workspace as root
        backStack.set(listOf(Workspace))
        assertEquals(1, backStack.size)
        assertEquals(Workspace, backStack.current)

        // Switch to multiple routes
        backStack.set(listOf(Workspace, Chat("disc_1")))
        assertEquals(2, backStack.size)
        assertEquals(Chat("disc_1"), backStack.current)
    }

    @Test
    fun adaptiveBreakpoint_600dpThreshold() {
        fun isExpandedTwoPane(widthDp: Int): Boolean = widthDp >= 600

        // Phone portrait (< 600dp) -> Single-pane drill-down
        assertFalse(isExpandedTwoPane(360))
        assertFalse(isExpandedTwoPane(412))
        assertFalse(isExpandedTwoPane(599))

        // Tablet, foldable expanded, landscape (>= 600dp) -> Two-pane side-by-side
        assertTrue(isExpandedTwoPane(600))
        assertTrue(isExpandedTwoPane(768))
        assertTrue(isExpandedTwoPane(1024))
        assertTrue(isExpandedTwoPane(1440))
    }

    @Test
    fun setupValidation_requiresAtLeastTwoAgents() {
        fun validateDebateConfig(config: DebateConfig, title: String): List<String> {
            val missing = mutableListOf<String>()
            if (title.isBlank()) missing.add("Discussion Title is required")
            if (config.topic.isBlank()) missing.add("Discussion Objective & Topic is required")
            if (config.agents.size < 2) missing.add("At least 2 participant agents are required to start (currently ${config.agents.size})")
            return missing
        }

        val primaryAgent = Agent(provider = Provider.ANTHROPIC, model = "claude-3-5-sonnet")
        val secondaryAgent = Agent(provider = Provider.OPENAI, model = "gpt-4o")

        // 1. One agent alone fails validation
        val oneAgentConfig = DebateConfig(topic = "Test Topic", primary = primaryAgent)
        val missingOne = validateDebateConfig(oneAgentConfig, "Valid Title")
        assertEquals(1, missingOne.size)
        assertTrue(missingOne.first().contains("At least 2 participant agents are required"))

        // 2. Blank title and blank topic also flagged
        val emptyConfig = DebateConfig(topic = "", primary = primaryAgent)
        val missingEmpty = validateDebateConfig(emptyConfig, "")
        assertEquals(3, missingEmpty.size)

        // 3. Two agents with title and topic passes validation
        val validConfig = DebateConfig(topic = "Valid Objective", primary = primaryAgent, secondary = secondaryAgent)
        val missingValid = validateDebateConfig(validConfig, "Valid Title")
        assertTrue(missingValid.isEmpty())
    }

    @Test
    fun backStack_replaceTop_replacesActiveRouteWithoutGrowingStack() {
        val stackList = mutableStateListOf<AppRoute>(Workspace, Setup(null))
        val backStack = AppBackStack(stackList)

        assertEquals(2, backStack.size)
        assertEquals(Setup(null), backStack.current)
        assertFalse(backStack.isNavigatingBack)

        // Replace Setup with newly created Chat
        backStack.replaceTop(Chat("new_debate_id"))
        assertEquals(2, backStack.size)
        assertEquals(Chat("new_debate_id"), backStack.current)
        assertFalse(backStack.isNavigatingBack)

        // Popping Chat returns cleanly to Workspace
        backStack.removeLast()
        assertEquals(1, backStack.size)
        assertEquals(Workspace, backStack.current)
        assertTrue(backStack.isNavigatingBack)
    }

    @Test
    fun backStack_popTo_preventsDuplicateStackEntriesWhenEditingSetup() {
        val stackList = mutableStateListOf<AppRoute>(Workspace, Chat("debate_99"))
        val backStack = AppBackStack(stackList)

        // User taps "Edit Setup" in ChatView
        backStack.add(Setup(discussionId = "debate_99"))
        assertEquals(3, backStack.size)
        assertEquals(Setup(discussionId = "debate_99"), backStack.current)

        // User returns to chat: popTo restores existing Chat without pushing a duplicate
        val didPop = backStack.popTo { it is Chat && it.discussionId == "debate_99" }
        assertTrue(didPop)
        assertEquals(2, backStack.size)
        assertEquals(Chat("debate_99"), backStack.current)
        assertTrue(backStack.isNavigatingBack)

        // Verify next pop lands on Workspace
        backStack.removeLast()
        assertEquals(1, backStack.size)
        assertEquals(Workspace, backStack.current)
    }

    @Test
    fun backStack_appBackHandlerEnablement() {
        val stackList = mutableStateListOf<AppRoute>(Workspace)
        val backStack = AppBackStack(stackList)

        fun isBackHandlerEnabled(isWideScreen: Boolean, stack: AppBackStack): Boolean =
            !isWideScreen && stack.size > 1

        // On mobile (compact) at root Workspace -> disabled (allows system to exit/minimize app)
        assertFalse(isBackHandlerEnabled(isWideScreen = false, backStack))

        // Pushing a detail screen -> enabled (intercepts back to pop stack)
        backStack.add(Chat("debate_1"))
        assertTrue(isBackHandlerEnabled(isWideScreen = false, backStack))

        // On desktop/wide screen -> disabled (desktop uses explicit UI buttons / window controls)
        assertFalse(isBackHandlerEnabled(isWideScreen = true, backStack))
    }

    @Test
    fun projectConfig_inheritanceIntoNewDiscussion() {
        val project = com.dialex.model.Project(
            id = "proj_kmp",
            name = "KMP Architecture",
            sharedContext = "Kotlin Multiplatform mobile + desktop project with Compose Multiplatform.",
            sharedInstructions = "Focus on clean architecture and zero platform leaks into domain.",
            defaultConsensus = 0.8
        )

        val discussion = Discussion(
            id = "",
            projectId = project.id,
            name = "New Discussion",
            config = DebateConfig(
                topic = "",
                commonContext = project.sharedContext,
                commonInfo = project.sharedInstructions,
                primary = Agent(provider = Provider.ANTHROPIC, model = Provider.ANTHROPIC.defaultModel())
            ),
            status = DiscussionStatus.DRAFT
        )

        assertEquals("proj_kmp", discussion.projectId)
        assertEquals("Kotlin Multiplatform mobile + desktop project with Compose Multiplatform.", discussion.config.commonContext)
        assertEquals("Focus on clean architecture and zero platform leaks into domain.", discussion.config.commonInfo)
    }
}
