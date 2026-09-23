package com.dialex.presentation.connect

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dialex.presentation.nav.AppBackStack
import com.dialex.presentation.nav.Setup

@Composable
fun ConnectRoute(
    backStack: AppBackStack,
    connectToLocalEngine: suspend () -> Unit,
    connectToRemote: suspend (url: String, username: String, password: String) -> Unit,
    supportsLocalEngine: Boolean
) {
    val viewModel: ConnectViewModel = viewModel { ConnectViewModel(connectToRemote, connectToLocalEngine) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(Unit) {
        viewModel.onIntent(ConnectIntent.Reset)
    }

    LaunchedEffect(viewModel, lifecycle) {
        viewModel.effect.flowWithLifecycle(lifecycle).collect { effect ->
            when (effect) {
                is ConnectEffect.NavigateToMain -> {
                    backStack.removeLast()
                    backStack.add(Setup(discussionId = null))
                }
            }
        }
    }

    ConnectScreen(
        state = state.copy(supportsLocalEngine = supportsLocalEngine),
        onIntent = viewModel::onIntent
    )
}
