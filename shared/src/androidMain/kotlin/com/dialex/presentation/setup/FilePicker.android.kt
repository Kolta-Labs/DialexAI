package com.dialex.presentation.setup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

actual class FilePicker actual constructor() {
    @Composable
    actual fun registerPicker(onFileSelected: (fileName: String, content: String) -> Unit): () -> Unit {
        val context = LocalContext.current
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                val fileName = queryFileName(context, uri) ?: "attachment.txt"
                val content = runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
                if (content != null) {
                    onFileSelected(fileName, content)
                }
            }
        }
        return {
            launcher.launch("*/*")
        }
    }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    @Composable
    actual fun registerImagePicker(onImageSelected: (fileName: String, base64DataUrl: String) -> Unit): () -> Unit {
        val context = LocalContext.current
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                val fileName = queryFileName(context, uri) ?: "icon.png"
                val dataUrl = runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null) {
                        val mime = context.contentResolver.getType(uri) ?: "image/png"
                        val base64 = kotlin.io.encoding.Base64.Default.encode(bytes)
                        "data:$mime;base64,$base64"
                    } else null
                }.getOrNull()
                if (dataUrl != null) {
                    onImageSelected(fileName, dataUrl)
                }
            }
        }
        return {
            launcher.launch("image/*")
        }
    }

    private fun queryFileName(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) return cursor.getString(idx)
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }
}
