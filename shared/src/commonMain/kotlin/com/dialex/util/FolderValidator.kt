package com.dialex.util

import com.dialex.model.FolderScope

sealed interface FolderValidationResult {
    data object Valid : FolderValidationResult
    data class Invalid(val reason: String) : FolderValidationResult
}

/**
 * Checks whether the given directory path exists, is a directory, and is accessible.
 */
expect fun checkFolderValidity(path: String): FolderValidationResult

/**
 * Validates a list of folders, returning a list of invalid folders paired with the failure reason.
 */
fun validateFolders(folders: List<FolderScope>): List<Pair<FolderScope, String>> {
    return folders.mapNotNull { folder ->
        when (val result = checkFolderValidity(folder.path)) {
            is FolderValidationResult.Valid -> null
            is FolderValidationResult.Invalid -> folder to result.reason
        }
    }
}
