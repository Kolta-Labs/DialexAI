package com.dialex.presentation.setup

import androidx.compose.runtime.Composable

/**
 * Common interface for triggering platform-native file picker.
 */
expect class FilePicker() {
    @Composable
    fun registerPicker(onFileSelected: (fileName: String, content: String) -> Unit): () -> Unit

    @Composable
    fun registerImagePicker(onImageSelected: (fileName: String, base64DataUrl: String) -> Unit): () -> Unit
}
