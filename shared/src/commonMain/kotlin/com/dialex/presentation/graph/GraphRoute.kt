package com.dialex.presentation.graph

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dialex.domain.repository.GraphRepository
import com.dialex.domain.usecase.DeleteGraphNodeUseCase
import com.dialex.domain.usecase.GetActiveGraphUseCase
import com.dialex.domain.usecase.SearchGraphNodesUseCase
import kotlinx.coroutines.flow.collectLatest

@Composable
fun GraphRoute(
    projectId: String,
    graphRepository: GraphRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null
) {
    val viewModel: GraphViewModel = viewModel {
        GraphViewModel(
            getActiveGraphUseCase = GetActiveGraphUseCase(graphRepository),
            searchGraphNodesUseCase = SearchGraphNodesUseCase(graphRepository),
            deleteGraphNodeUseCase = DeleteGraphNodeUseCase(graphRepository)
        )
    }

    val state by viewModel.state.collectAsState()

    LaunchedEffect(projectId) {
        viewModel.onIntent(GraphIntent.LoadGraph(projectId))
    }

    LaunchedEffect(viewModel.effects) {
        viewModel.effects.collect { effect: GraphEffect ->
            when (effect) {
                is GraphEffect.ShowSnackbar -> {
                    snackbarHostState?.showSnackbar(effect.message)
                }
                is GraphEffect.NavigateToDebate -> {
                    // Handled if navigating to debate
                }
            }
        }
    }

    GraphScreen(
        state = state,
        onIntent = viewModel::onIntent,
        onBack = onBack,
        modifier = modifier
    )
}
