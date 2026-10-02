package com.artix.desktop.mvi

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
 * Kolt-compliant MVI ViewModel for Artix Cockpit.
 */
abstract class MviViewModel<S : Any, I : Any, E : Any>(initialState: S) : ViewModel() {

    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<S> = _state.asStateFlow()

    private val _effect = Channel<E>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()

    protected fun setState(reducer: S.() -> S) = _state.update(reducer)

    protected fun sendEffect(effect: E) = viewModelScope.launch { _effect.send(effect) }

    abstract fun onIntent(intent: I)
}

sealed interface AsyncState<out T> {
    data object Uninitialized : AsyncState<Nothing>
    data object Loading : AsyncState<Nothing>
    data class Success<out T>(val data: T) : AsyncState<T>
    data class Error(val message: String, val cause: Throwable? = null) : AsyncState<Nothing>

    val valueOrNull: T?
        get() = (this as? Success<T>)?.data
}
