package com.dialex.util

import com.dialex.model.FolderScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FolderValidatorTest {

    @Test
    fun testBlankPathIsInvalid() {
        val res = checkFolderValidity("   ")
        assertTrue(res is FolderValidationResult.Invalid)
        assertEquals("Path cannot be empty", res.reason)
    }

    @Test
    fun testNonExistentFolderIsInvalid() {
        val nonExistentPath = "/non_existent_folder_dialex_test_xyz_12345"
        val res = checkFolderValidity(nonExistentPath)
        assertTrue(res is FolderValidationResult.Invalid)
        assertTrue(res.reason.contains("does not exist"))
    }

    @Test
    fun testValidateFoldersFiltersInvalid() {
        val validFolder = FolderScope(path = ".", isReadOnly = true)
        val invalidFolder = FolderScope(path = "/non_existent_path_dialex_9999", isReadOnly = false)

        val invalidList = validateFolders(listOf(validFolder, invalidFolder))
        assertEquals(1, invalidList.size)
        assertEquals(invalidFolder.path, invalidList[0].first.path)
    }
}
