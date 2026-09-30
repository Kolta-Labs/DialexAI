package com.dialex.util

import java.io.File

actual fun checkFolderValidity(path: String): FolderValidationResult {
    if (path.isBlank()) {
        return FolderValidationResult.Invalid("Path cannot be empty")
    }
    return try {
        val file = File(path)
        when {
            !file.exists() -> FolderValidationResult.Invalid("Folder does not exist or was removed")
            !file.isDirectory -> FolderValidationResult.Invalid("Path is a file, not a directory")
            !file.canRead() -> FolderValidationResult.Invalid("Folder is not readable or permission is denied")
            else -> FolderValidationResult.Valid
        }
    } catch (e: Exception) {
        FolderValidationResult.Invalid(e.message ?: "Invalid folder path")
    }
}
