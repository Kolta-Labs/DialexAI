package com.dialex.presentation.setup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.flowWithLifecycle
import com.dialex.domain.repository.DiscussionRepository
import com.dialex.domain.repository.ProjectRepository
import com.dialex.domain.repository.SettingsRepository
import com.dialex.domain.repository.TemplateRepository
import com.dialex.model.PersonaSelectionResult
import com.dialex.presentation.nav.AppBackHandler
import com.dialex.presentation.nav.AppBackStack
import com.dialex.presentation.nav.Chat
import com.dialex.presentation.nav.PersonaBuilder
import com.dialex.presentation.nav.Settings
import com.dialex.presentation.settings.SettingsTab

@Composable
fun SetupRoute(
    backStack: AppBackStack,
    discussionId: String?,
    initialProjectId: String? = null,
    copyFromDiscussionId: String? = null,
    supportsCli: Boolean,
    projectRepository: ProjectRepository,
    discussionRepository: DiscussionRepository,
    settingsRepository: SettingsRepository,
    templateRepository: TemplateRepository,
    onToggleSidebar: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    isCompact: Boolean = false,
) {
    val viewModel: SetupViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        key = "setup_${discussionId}_${initialProjectId}_${copyFromDiscussionId}"
    ) {
        SetupViewModel(
            projectRepository = projectRepository,
            discussionRepository = discussionRepository,
            settingsRepository = settingsRepository,
            templateRepository = templateRepository,
            discussionId = discussionId,
            initialProjectId = initialProjectId,
            copyFromDiscussionId = copyFromDiscussionId,
            supportsCli = supportsCli
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.effect.flowWithLifecycle(lifecycle).collect { effect ->
            when (effect) {
                is SetupEffect.NavigateToChat -> {
                    val existingChatIndex = backStack.entries.indexOfLast { it is Chat && it.discussionId == effect.discussionId }
                    if (existingChatIndex >= 0) {
                        // Chat was already in the stack before editing setup: pop back to that Chat
                        backStack.popTo { it is Chat && it.discussionId == effect.discussionId }
                    } else {
                        // New discussion created from Setup: replace Setup with Chat
                        backStack.replaceTop(Chat(effect.discussionId))
                    }
                }
                is SetupEffect.NavigateBack -> {
                    if (onBack != null) {
                        onBack()
                    } else if (backStack.size > 1) {
                        backStack.removeLast()
                    } else if (discussionId != null) {
                        backStack.replaceTop(Chat(discussionId))
                    } else {
                        backStack.set(listOf(com.dialex.presentation.nav.Workspace))
                    }
                }
                SetupEffect.NavigateToSettings -> backStack.add(Settings(SettingsTab.AiAgents))
                SetupEffect.NavigateToPersonaBuilder -> backStack.add(PersonaBuilder(null))
                is SetupEffect.ShowSnackbar -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
            }
        }
    }

    // Handle back gestures
    AppBackHandler(enabled = state.activeAgentForPersonaPicker != null) {
        viewModel.onIntent(SetupIntent.DismissPersonaPicker)
    }

    AppBackHandler(enabled = state.activeAgentForPersonaPicker == null && discussionId == null && state.step == SetupStep.ConfigForm) {
        viewModel.onIntent(SetupIntent.NavigateToFrontPage)
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = when {
                state.activeAgentForPersonaPicker != null -> "PersonaPicker"
                state.step == SetupStep.FrontPage -> "FrontPage"
                else -> "ConfigForm"
            },
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "SetupStageTransition"
        ) { stage ->
            when (stage) {
                "PersonaPicker" -> {
                    val activeIndex = state.activeAgentForPersonaPicker ?: 0
                    val currentPersonaId = state.discussion?.config?.agents?.getOrNull(activeIndex)?.personaId
                    PersonaPickerScreen(
                        agentIndex = activeIndex,
                        currentPersonaId = currentPersonaId,
                        availablePersonas = state.availablePersonas,
                        discussions = state.otherDiscussions,
                        onSelectPersona = { result ->
                            viewModel.onIntent(SetupIntent.ApplyPersonaToAgent(activeIndex, result))
                        },
                        onDismiss = { viewModel.onIntent(SetupIntent.DismissPersonaPicker) },
                        onOpenPersonaBuilder = { backStack.add(PersonaBuilder(null)) },
                        isCompact = isCompact,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                "FrontPage" -> {
                    SetupFrontPage(
                        state = state,
                        onIntent = viewModel::onIntent,
                        onToggleSidebar = onToggleSidebar,
                        onBack = onBack,
                        isCompact = isCompact,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                else -> {
                    SetupScreen(
                        state = state,
                        onIntent = viewModel::onIntent,
                        onToggleSidebar = onToggleSidebar,
                        onBack = if (discussionId == null) {
                            { viewModel.onIntent(SetupIntent.NavigateToFrontPage) }
                        } else onBack,
                        onManagePersonas = { backStack.add(Settings(SettingsTab.Personas)) },
                        isCompact = isCompact,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
