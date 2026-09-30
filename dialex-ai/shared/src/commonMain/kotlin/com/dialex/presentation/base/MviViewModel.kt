package com.dialex.presentation.base

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Base ViewModel for every screen in this project. Follows the MVI contract described in
 * presentation-mvi.md:
 *
 * - [state] — a [StateFlow] of the current [S]; collected by the Route composable with
 *   `collectAsStateWithLifecycle`.
 * - [effect] — a buffered [Channel]-based [kotlinx.coroutines.flow.Flow] of one-shot [E]
 *   events (navigation, snackbar). Collected once in the Route composable using
 *   `flowWithLifecycle`.
 * - [onIntent] — the single entry point for every user action.
 *
 * Note: Channel-based effects are kept deliberately per the project's MVI contract, even
 * though current official Android guidance prefers modeling events as State. See
 * presentation-mvi.md §"Known deviation from current official guidance" for the rationale.
 */
abstract class MviViewModel<S : Any, I : Any, E : Any>(initialState: S) : ViewModel() {

    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<S> = _state.asStateFlow()

    private val _effect = Channel<E>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()

    /** Apply a reducer to produce the next [State]. Thread-safe via [MutableStateFlow.update]. */
    protected fun setState(reducer: S.() -> S) = _state.update(reducer)

    /** Enqueue a one-shot [Effect]. Buffered — won't block the caller. */
    protected fun sendEffect(effect: E) = viewModelScope.launch { _effect.send(effect) }

    /** Every user action routes through here. Subclasses implement their intent handling. */
    abstract fun onIntent(intent: I)
}
