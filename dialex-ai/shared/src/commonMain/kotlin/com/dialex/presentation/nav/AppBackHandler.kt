package com.dialex.presentation.nav

import androidx.compose.runtime.Composable

/**
 * Multiplatform system back button & gesture handler.
 * On Android, intercepts system back gestures/keys via BackHandler.
 * On Desktop and other targets, this is a no-op.
 */
@Composable
expect fun AppBackHandler(enabled: Boolean = true, onBack: () -> Unit)
