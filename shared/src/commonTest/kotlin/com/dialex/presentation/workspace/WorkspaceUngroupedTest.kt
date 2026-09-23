package com.dialex.presentation.workspace

import com.dialex.model.Agent
import com.dialex.model.DebateConfig
import com.dialex.model.Discussion
import com.dialex.model.Project
import com.dialex.model.Provider
import com.dialex.model.defaultModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceUngroupedTest {

    private fun testDiscussion(
        id: String,
        projectId: String,
        name: String,
        updatedAt: Long = 0L,
        createdAt: Long = 0L
    ) = Discussion(
        id = id,
        projectId = projectId,
        name = name,
        updatedAt = updatedAt,
        createdAt = createdAt,
        config = DebateConfig(
            topic = "",
            primary = Agent(provider = Provider.ANTHROPIC, model = Provider.ANTHROPIC.defaultModel())
        )
    )

    private fun Discussion.lastActiveTimestamp(): Long {
        if (updatedAt > 0L) return updatedAt
        if (createdAt > 0L) return createdAt
        return transcript.lastOrNull()?.timestampMs ?: 0L
    }

    private fun Project.lastActiveTimestamp(discussions: List<Discussion>, projects: List<Project>): Long {
        val isUngrouped = name.equals("Ungrouped", ignoreCase = true)
        val projectDiscussions = if (isUngrouped) {
            discussions.filter { it.projectId == id || it.projectId.isNullOrBlank() || projects.none { p -> p.id == it.projectId } }
        } else {
            discussions.filter { it.projectId == id }
        }
        return projectDiscussions.maxOfOrNull { it.lastActiveTimestamp() } ?: 0L
    }

    private fun computeSortedProjects(
        projects: List<Project>,
        sortOrder: ProjectSortOrder,
        discussions: List<Discussion>
    ): List<Project> {
        val base = when (sortOrder) {
            ProjectSortOrder.LAST_ACTIVE -> projects.sortedByDescending { it.lastActiveTimestamp(discussions, projects) }
            ProjectSortOrder.NAME_ASC -> projects.sortedBy { it.name.lowercase() }
            ProjectSortOrder.NAME_DESC -> projects.sortedByDescending { it.name.lowercase() }
            ProjectSortOrder.DATE_NEWEST -> projects.reversed()
            ProjectSortOrder.DATE_OLDEST -> projects
        }
        val ungrouped = base.filter { proj ->
            proj.name.equals("Ungrouped", ignoreCase = true) &&
                discussions.any { it.projectId == proj.id || it.projectId.isNullOrBlank() || projects.none { p -> p.id == it.projectId } }
        }
        val others = base.filterNot { it.name.equals("Ungrouped", ignoreCase = true) }
        return others + ungrouped
    }

    @Test
    fun ungroupedProject_partitionsDiscussionsWithoutProject() {
        val ungrouped = Project(id = "p-ungrouped", name = "Ungrouped")
        val otherProject = Project(id = "p-cli", name = "CLI Project")
        val projects = listOf(ungrouped, otherProject)

        val disc1 = testDiscussion("d1", "p-cli", "CLI Discussion")
        val disc2 = testDiscussion("d2", "p-ungrouped", "Direct Ungrouped")
        val disc3 = testDiscussion("d3", "", "Empty Project ID")
        val disc4 = testDiscussion("d4", "non-existent-proj", "Orphan Discussion")

        val allDiscussions = listOf(disc1, disc2, disc3, disc4)

        // For "Ungrouped" project, include matching, blank, or non-existent project IDs
        val ungroupedDiscussions = allDiscussions.filter {
            it.projectId == ungrouped.id || it.projectId.isNullOrBlank() || projects.none { p -> p.id == it.projectId }
        }

        assertEquals(3, ungroupedDiscussions.size)
        assertTrue(ungroupedDiscussions.any { it.id == "d2" })
        assertTrue(ungroupedDiscussions.any { it.id == "d3" })
        assertTrue(ungroupedDiscussions.any { it.id == "d4" })

        // For other project, strict match
        val cliDiscussions = allDiscussions.filter { it.projectId == otherProject.id }
        assertEquals(1, cliDiscussions.size)
        assertEquals("d1", cliDiscussions.first().id)
    }

    @Test
    fun defaultProjectId_resolvesToUngrouped_whenNoProjectSelected() {
        val ungrouped = Project(id = "p-ungrouped", name = "Ungrouped")
        val otherProject = Project(id = "p-cli", name = "CLI Project")
        val projects = listOf(ungrouped, otherProject)

        val selectedProjectId: String? = null
        val projId: String? = null

        val ungroupedId = projects.firstOrNull { it.name.equals("Ungrouped", ignoreCase = true) }?.id
        val targetProjId = projId ?: (selectedProjectId ?: ungroupedId) ?: projects.firstOrNull()?.id

        assertEquals("p-ungrouped", targetProjId)
    }

    @Test
    fun sortedProjects_hidesUngroupedWhenNoDiscussionsExist() {
        val ungrouped = Project(id = "p-ungrouped", name = "Ungrouped")
        val alpha = Project(id = "p-alpha", name = "Alpha")
        val beta = Project(id = "p-beta", name = "Beta")
        val projects = listOf(ungrouped, alpha, beta)

        // Only discussions inside alpha and beta
        val discussions = listOf(
            testDiscussion("d1", "p-alpha", "Alpha Disc"),
            testDiscussion("d2", "p-beta", "Beta Disc")
        )

        val result = computeSortedProjects(projects, ProjectSortOrder.LAST_ACTIVE, discussions)

        assertFalse(result.any { it.name.equals("Ungrouped", ignoreCase = true) })
        assertEquals(listOf("Alpha", "Beta"), result.map { it.name })
    }

    @Test
    fun sortedProjects_showsUngroupedAtBottomWhenDiscussionsExist() {
        val ungrouped = Project(id = "p-ungrouped", name = "Ungrouped")
        val alpha = Project(id = "p-alpha", name = "Alpha")
        val beta = Project(id = "p-beta", name = "Beta")
        val projects = listOf(ungrouped, alpha, beta)

        // One discussion is ungrouped
        val discussions = listOf(
            testDiscussion("d1", "p-alpha", "Alpha Disc"),
            testDiscussion("d2", "p-beta", "Beta Disc"),
            testDiscussion("d3", "p-ungrouped", "Ungrouped Disc")
        )

        val result = computeSortedProjects(projects, ProjectSortOrder.NAME_ASC, discussions)

        assertTrue(result.any { it.name.equals("Ungrouped", ignoreCase = true) })
        assertEquals("p-ungrouped", result.last().id)
        assertEquals(listOf("Alpha", "Beta", "Ungrouped"), result.map { it.name })
    }

    @Test
    fun sortedProjects_sortsByLastActiveDefault() {
        val ungrouped = Project(id = "p-ungrouped", name = "Ungrouped")
        val projOld = Project(id = "p-old", name = "Old Active Project")
        val projNew = Project(id = "p-new", name = "Recently Active Project")
        val projects = listOf(ungrouped, projOld, projNew)

        val discussions = listOf(
            testDiscussion("d1", "p-old", "Old Disc", updatedAt = 1000L),
            testDiscussion("d2", "p-new", "New Disc", updatedAt = 5000L)
        )

        val result = computeSortedProjects(projects, ProjectSortOrder.LAST_ACTIVE, discussions)

        assertEquals("p-new", result.first().id)
        assertEquals("p-old", result[1].id)
        // Ungrouped is omitted since it has no discussions
        assertEquals(2, result.size)
    }

    @Test
    fun sortedProjects_alphabeticalAscendingAndDescending() {
        val pA = Project(id = "p-a", name = "Apple")
        val pZ = Project(id = "p-z", name = "Zebra")
        val pM = Project(id = "p-m", name = "Mango")
        val projects = listOf(pA, pZ, pM)

        val asc = computeSortedProjects(projects, ProjectSortOrder.NAME_ASC, emptyList())
        assertEquals(listOf("Apple", "Mango", "Zebra"), asc.map { it.name })

        val desc = computeSortedProjects(projects, ProjectSortOrder.NAME_DESC, emptyList())
        assertEquals(listOf("Zebra", "Mango", "Apple"), desc.map { it.name })
    }
}
