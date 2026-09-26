package com.dialex.presentation.settings.personas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dialex.domain.repository.PersonaRepository
import com.dialex.presentation.nav.AppBackStack
import kotlinx.coroutines.launch

@Composable
fun PersonaBuilderRoute(
    backStack: AppBackStack,
    personaId: String?,
    personaRepository: PersonaRepository,
    onBack: (() -> Unit)? = null,
    isCompact: Boolean = false,
) {
    val viewModel: PersonaBuilderViewModel = viewModel(key = "persona_${personaId}") { PersonaBuilderViewModel(personaRepository, personaId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(viewModel) {
        viewModel.effect.flowWithLifecycle(lifecycle).collect { effect ->
            when (effect) {
                is PersonaBuilderEffect.NavigateBack -> {
                    if (onBack != null) onBack() else { backStack.removeLast(); Unit }
                }
                is PersonaBuilderEffect.ShowSnackbar -> {
                    scope.launch {
                        snackbarHostState.showSnackbar(effect.message)
                    }
                }
            }
        }
    }

    if (state.isDnaStudioOpen) {
        com.dialex.presentation.settings.personas.dna.PersonaDnaRoute(
            personaRepository = personaRepository,
            personaId = if (state.isEditing) state.draft.id else null,
            initialDna = state.draft.dna,
            onBack = { viewModel.onIntent(PersonaBuilderIntent.ToggleDnaStudio(false)) },
            onApplyToPersona = { updatedDna, compiledPrompt ->
                viewModel.onIntent(PersonaBuilderIntent.ApplyDnaToDraft(updatedDna, compiledPrompt))
            }
        )
    } else {
        Box(Modifier.fillMaxSize()) {
            PersonaBuilderScreen(
                state = state,
                onIntent = viewModel::onIntent,
                onBack = onBack ?: { backStack.removeLast(); Unit },
                isCompact = isCompact,
                modifier = Modifier.fillMaxSize(),
            )
            com.dialex.ui.ThemedSnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            )
        }
    }
}
