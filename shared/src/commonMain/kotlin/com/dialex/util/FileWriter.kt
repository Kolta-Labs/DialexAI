package com.dialex.util

/**
 * Writes [content] into a file named [fileName] inside [folderPath].
 * Returns true if the file was written successfully, false otherwise.
 */
expect fun writeTextFile(folderPath: String, fileName: String, content: String, append: Boolean = false): Boolean
