package com.dialex.presentation.setup

import androidx.compose.runtime.Composable
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

actual class FilePicker actual constructor() {
    @Composable
    actual fun registerPicker(onFileSelected: (fileName: String, content: String) -> Unit): () -> Unit {
        return {
            val dialog = FileDialog(null as Frame?, "Select File", FileDialog.LOAD)
            dialog.isVisible = true
            val selectedFile = dialog.file
            val selectedDir = dialog.directory
            if (selectedFile != null && selectedDir != null) {
                val file = File(selectedDir, selectedFile)
                runCatching {
                    val content = file.readText()
                    onFileSelected(file.name, content)
                }
            }
        }
    }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    @Composable
    actual fun registerImagePicker(onImageSelected: (fileName: String, base64DataUrl: String) -> Unit): () -> Unit {
        return {
            val dialog = FileDialog(null as Frame?, "Select Persona Icon Image", FileDialog.LOAD)
            dialog.setFilenameFilter { _, name ->
                val lower = name.lowercase()
                lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp") || lower.endsWith(".svg")
            }
            dialog.isVisible = true
            val selectedFile = dialog.file
            val selectedDir = dialog.directory
            if (selectedFile != null && selectedDir != null) {
                val file = File(selectedDir, selectedFile)
                runCatching {
                    val bytes = file.readBytes()
                    val mime = when {
                        file.name.endsWith(".png", ignoreCase = true) -> "image/png"
                        file.name.endsWith(".jpg", ignoreCase = true) || file.name.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
                        file.name.endsWith(".webp", ignoreCase = true) -> "image/webp"
                        file.name.endsWith(".svg", ignoreCase = true) -> "image/svg+xml"
                        else -> "image/png"
                    }
                    val base64 = kotlin.io.encoding.Base64.Default.encode(bytes)
                    val dataUrl = "data:$mime;base64,$base64"
                    onImageSelected(file.name, dataUrl)
                }
            }
        }
    }
}
