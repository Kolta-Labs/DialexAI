package com.dialex.presentation.arena

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.dialex.domain.repository.BenchmarkRepository
import kotlinx.coroutines.flow.collectLatest

@Composable
fun BenchmarkRoute(
    benchmarkRepository: BenchmarkRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel = remember(benchmarkRepository) {
        BenchmarkViewModel(benchmarkRepository)
    }

    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.effect.collectLatest { effect ->
            when (effect) {
                is BenchmarkEffect.ShowSnackbar -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
                is BenchmarkEffect.ExportDownloaded -> {
                    // Exported content ready
                }
            }
        }
    }

    BenchmarkScreen(
        state = state,
        onIntent = viewModel::onIntent,
        onBack = onBack,
        modifier = modifier
    )
}
