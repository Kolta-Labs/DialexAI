package com.dialex.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * CompositionLocal providing an action to toggle window maximization / zoom on desktop.
 * On mobile platforms, this is null.
 */
val LocalWindowMaximizeToggle = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * Modifier that listens for double-clicks / double-taps on title bar / top app bar areas,
 * invoking [action] to toggle window size expansion.
 */
fun Modifier.onWindowTitleBarDoubleClick(action: (() -> Unit)?): Modifier {
    if (action == null) return this
    return this.pointerInput(action) {
        detectTapGestures(
            onDoubleTap = {
                action()
            }
        )
    }
}

/**
 * Convenience composable modifier that resolves [LocalWindowMaximizeToggle.current] to handle double-clicks.
 */
@Composable
fun Modifier.windowTitleBarDoubleClick(): Modifier =
    onWindowTitleBarDoubleClick(LocalWindowMaximizeToggle.current)
