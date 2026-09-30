package com.dialex.presentation.settings.personas.dna

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dialex.domain.model.PersonaDNA
import com.dialex.domain.repository.PersonaRepository
import com.dialex.domain.usecase.CompileDnaPromptUseCase
import com.dialex.domain.usecase.ExportPersonaDnaUseCase
import com.dialex.domain.usecase.GetPersonaDnaUseCase
import com.dialex.domain.usecase.ImportPersonaDnaUseCase
import com.dialex.domain.usecase.ListBuiltinHeuristicsUseCase
import com.dialex.domain.usecase.UpdatePersonaDnaUseCase
import com.dialex.presentation.nav.AppBackStack
import com.dialex.ui.ThemedSnackbarHost
import kotlinx.coroutines.launch

@Composable
fun PersonaDnaRoute(
    personaRepository: PersonaRepository,
    backStack: AppBackStack? = null,
    personaId: String? = null,
    initialDna: PersonaDNA? = null,
    onBack: (() -> Unit)? = null,
    onApplyToPersona: ((PersonaDNA, String?) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val viewModel: PersonaDnaViewModel = viewModel(key = "dna_${personaId ?: "draft"}") {
        PersonaDnaViewModel(
            getPersonaDnaUseCase = GetPersonaDnaUseCase(personaRepository),
            updatePersonaDnaUseCase = UpdatePersonaDnaUseCase(personaRepository),
            listBuiltinHeuristicsUseCase = ListBuiltinHeuristicsUseCase(personaRepository),
            compileDnaPromptUseCase = CompileDnaPromptUseCase(personaRepository),
            importPersonaDnaUseCase = ImportPersonaDnaUseCase(personaRepository),
            exportPersonaDnaUseCase = ExportPersonaDnaUseCase(personaRepository),
            initialDna = initialDna,
            personaId = personaId
        )
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(viewModel) {
        viewModel.effect.flowWithLifecycle(lifecycle).collect { effect ->
            when (effect) {
                is PersonaDnaEffect.ShowSnackbar -> {
                    scope.launch { snackbarHostState.showSnackbar(effect.message) }
                }
                is PersonaDnaEffect.DnaExported -> {}
                is PersonaDnaEffect.PromptCompiled -> {
                    scope.launch { snackbarHostState.showSnackbar("Compiled cognitive DNA mandate!") }
                }
                is PersonaDnaEffect.DnaSaved -> {}
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        PersonaDnaStudioScreen(
            state = state,
            onIntent = viewModel::onIntent,
            onBack = onBack ?: { backStack?.removeLast(); Unit },
            onApplyToPersona = onApplyToPersona,
            modifier = Modifier.fillMaxSize()
        )
        ThemedSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
        )
    }
}
