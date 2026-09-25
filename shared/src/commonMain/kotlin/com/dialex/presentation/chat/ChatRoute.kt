package com.dialex.presentation.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dialex.export.exportMemoFileName
import com.dialex.export.toExecutiveMemorandumHtml
import com.dialex.domain.repository.DiscussionRepository
import com.dialex.model.Discussion
import com.dialex.presentation.nav.AppBackStack
import com.dialex.presentation.nav.Setup

import com.dialex.presentation.nav.Settings
import com.dialex.presentation.settings.SettingsTab

@Composable
fun ChatRoute(
    backStack: AppBackStack,
    discussionId: String,
    tokenBudget: Int,
    discussionRepository: DiscussionRepository,
    onExportMarkdown: (markdown: String, fileName: String) -> Unit,
    onBack: (() -> Unit)? = null,
    projectName: String? = null,
    onEditSetup: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    onOpenTokenSettings: (() -> Unit)? = null,
    artifactsPaneOpen: Boolean = false,
    onToggleArtifacts: (() -> Unit)? = null,
    summaryDialogOpen: Boolean = false,
    onToggleSummary: (() -> Unit)? = null,
    onDiscussionUpdated: ((Discussion) -> Unit)? = null,
    searchQuery: String = "",
    onSearchQueryChange: ((String) -> Unit)? = null,
    isSearchActive: Boolean = false,
    onToggleSearch: ((Boolean) -> Unit)? = null,
    searchNextTrigger: Int = 0,
    searchPrevTrigger: Int = 0,
    onSearchMatchesChanged: ((count: Int, currentIndex: Int) -> Unit)? = null,
    isCompact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val conductSocraticTurnUseCase = remember(discussionRepository) { com.dialex.domain.usecase.ConductSocraticTurnUseCase(discussionRepository) }
    val generateSocraticDigestUseCase = remember(discussionRepository) { com.dialex.domain.usecase.GenerateSocraticDigestUseCase(discussionRepository) }
    val elevateSocraticToCouncilUseCase = remember(discussionRepository) { com.dialex.domain.usecase.ElevateSocraticToCouncilUseCase(discussionRepository) }

    val viewModel: ChatViewModel = viewModel(key = "chat_$discussionId") {
        ChatViewModel(
            discussionRepository = discussionRepository,
            discussionId = discussionId,
            tokenBudget = tokenBudget,
            conductSocraticTurnUseCase = conductSocraticTurnUseCase,
            generateSocraticDigestUseCase = generateSocraticDigestUseCase,
            elevateSocraticToCouncilUseCase = elevateSocraticToCouncilUseCase,
        )
    }

    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.discussion) {
        state.discussion?.let { onDiscussionUpdated?.invoke(it) }
    }

    var snackbarMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel.effect) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is ChatEffect.NavigateBack -> {
                    if (onBack != null) onBack() else backStack.removeLast()
                }
                is ChatEffect.NavigateToSetup -> {
                    if (onEditSetup != null) onEditSetup() else backStack.add(Setup(effect.discussionId))
                }
                is ChatEffect.ExportMarkdown -> onExportMarkdown(effect.markdown, effect.suggestedFileName)
                is ChatEffect.ShowSnackbar -> snackbarMessage = effect.message
                is ChatEffect.ScrollToBottom -> {}
                is ChatEffect.ElevateSuccess -> backStack.add(Setup(effect.newDiscussionId))
            }
        }
    }

    ChatScreen(
        state = state,
        onIntent = viewModel::onIntent,
        onBack = onBack ?: { backStack.removeLast(); Unit },
        projectName = projectName,
        onEditSetup = onEditSetup ?: { backStack.add(Setup(discussionId)) },
        onExportMarkdown = { viewModel.onIntent(ChatIntent.ExportMarkdown) },
        onExportMemo = {
            state.discussion?.let { onExportMarkdown(it.toExecutiveMemorandumHtml(projectName), it.exportMemoFileName()) }
        },
        onOpenSettings = onOpenSettings ?: { backStack.add(Settings(SettingsTab.Compaction)) },
        onOpenTokenSettings = onOpenTokenSettings ?: { backStack.add(Settings(SettingsTab.Limits)) },
        isCompact = isCompact,
        externalArtifactsPaneOpen = artifactsPaneOpen,
        onExternalToggleArtifacts = onToggleArtifacts,
        externalSummaryDialogOpen = summaryDialogOpen,
        onExternalToggleSummary = onToggleSummary,
        externalSearchQuery = searchQuery,
        onExternalSearchQueryChange = onSearchQueryChange,
        externalSearchActive = isSearchActive,
        onExternalToggleSearch = onToggleSearch,
        externalSearchNextTrigger = searchNextTrigger,
        externalSearchPrevTrigger = searchPrevTrigger,
        onSearchMatchesChanged = onSearchMatchesChanged,
        snackbarMessage = snackbarMessage,
        onClearSnackbar = { snackbarMessage = null },
        modifier = modifier
    )
}
