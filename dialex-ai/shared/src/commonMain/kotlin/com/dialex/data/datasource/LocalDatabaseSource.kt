package com.dialex.data.datasource

import com.dialex.data.db.SqlCursor
import com.dialex.data.db.SqlDatabase
import com.dialex.domain.model.SyncMetadata
import com.dialex.domain.model.SyncStatus
import com.dialex.model.DebateConfig
import com.dialex.model.DebateMessage
import com.dialex.model.Discussion
import com.dialex.model.DiscussionStatus
import com.dialex.model.Project
import com.dialex.model.Provider
import com.dialex.model.defaultModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LocalDatabaseSource(private val db: SqlDatabase) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    private val discussionsFlow = MutableStateFlow<Map<String, List<Discussion>>>(emptyMap())

    // ── Projects ──────────────────────────────────────────────────────────────

    fun getProjects(profileId: String): List<Project> {
        val sql = """
            SELECT id, name, shared_context, shared_instructions, default_consensus
            FROM projects
            WHERE profile_id = ? AND is_deleted = 0
            ORDER BY created_at ASC
        """.trimIndent()

        return db.query(sql, listOf(profileId)) { cursor ->
            Project(
                id = cursor.getString(0) ?: "",
                name = cursor.getString(1) ?: "",
                sharedContext = cursor.getString(2) ?: "",
                sharedInstructions = cursor.getString(3) ?: "",
                defaultConsensus = cursor.getDouble(4)
            )
        }
    }

    fun getProject(profileId: String, id: String): Project? {
        val sql = """
            SELECT id, name, shared_context, shared_instructions, default_consensus
            FROM projects
            WHERE profile_id = ? AND id = ? AND is_deleted = 0
            LIMIT 1
        """.trimIndent()

        return db.querySingle(sql, listOf(profileId, id)) { cursor ->
            Project(
                id = cursor.getString(0) ?: "",
                name = cursor.getString(1) ?: "",
                sharedContext = cursor.getString(2) ?: "",
                sharedInstructions = cursor.getString(3) ?: "",
                defaultConsensus = cursor.getDouble(4)
            )
        }
    }

    fun saveProject(profileId: String, project: Project): Project {
        val now = System.currentTimeMillis()
        val currentVersion = db.querySingle(
            "SELECT version FROM projects WHERE id = ? AND profile_id = ?",
            listOf(project.id, profileId)
        ) { it.getLong(0) } ?: 0L

        val nextVersion = currentVersion + 1L

        val sql = """
            INSERT OR REPLACE INTO projects (
                id, profile_id, name, shared_context, shared_instructions, default_consensus,
                created_at, updated_at, version, is_deleted, sync_status
            ) VALUES (?, ?, ?, ?, ?, ?, COALESCE((SELECT created_at FROM projects WHERE id = ?), ?), ?, ?, 0, 'DIRTY_LOCAL')
        """.trimIndent()

        db.exec(
            sql,
            listOf(
                project.id,
                profileId,
                project.name,
                project.sharedContext,
                project.sharedInstructions,
                project.defaultConsensus,
                project.id,
                now,
                now,
                nextVersion
            )
        )
        return project
    }

    fun deleteProject(profileId: String, id: String) {
        val now = System.currentTimeMillis()
        val sql = "UPDATE projects SET is_deleted = 1, updated_at = ?, sync_status = 'DIRTY_LOCAL' WHERE profile_id = ? AND id = ?"
        db.exec(sql, listOf(now, profileId, id))
    }

    // ── Discussions ───────────────────────────────────────────────────────────

    fun getDiscussions(profileId: String, projectId: String? = null): List<Discussion> {
        val sql = if (projectId != null) {
            """
                SELECT id, project_id, name, status, config_json, conclusion, handoff_prompt, total_tokens_used
                FROM discussions
                WHERE profile_id = ? AND project_id = ? AND is_deleted = 0
                ORDER BY updated_at DESC, created_at DESC
            """.trimIndent()
        } else {
            """
                SELECT id, project_id, name, status, config_json, conclusion, handoff_prompt, total_tokens_used
                FROM discussions
                WHERE profile_id = ? AND is_deleted = 0
                ORDER BY updated_at DESC, created_at DESC
            """.trimIndent()
        }

        val args = if (projectId != null) listOf(profileId, projectId) else listOf(profileId)
        val discussions = db.query(sql, args) { cursor -> mapDiscussion(cursor) }

        // Attach transcript messages
        return discussions.map { disc ->
            disc.copy(transcript = getMessages(disc.id))
        }
    }

    fun getDiscussion(profileId: String, id: String): Discussion? {
        val sql = """
            SELECT id, project_id, name, status, config_json, conclusion, handoff_prompt, total_tokens_used
            FROM discussions
            WHERE profile_id = ? AND id = ? AND is_deleted = 0
            LIMIT 1
        """.trimIndent()

        val disc = db.querySingle(sql, listOf(profileId, id)) { cursor -> mapDiscussion(cursor) } ?: return null
        return disc.copy(transcript = getMessages(disc.id))
    }

    fun saveDiscussion(profileId: String, discussion: Discussion): Discussion {
        val now = System.currentTimeMillis()
        val currentVersion = db.querySingle(
            "SELECT version FROM discussions WHERE id = ? AND profile_id = ?",
            listOf(discussion.id, profileId)
        ) { it.getLong(0) } ?: 0L

        val nextVersion = currentVersion + 1L
        val configJson = json.encodeToString(discussion.config)

        db.transaction {
            val sql = """
                INSERT OR REPLACE INTO discussions (
                    id, profile_id, project_id, name, status, config_json, conclusion, handoff_prompt,
                    total_tokens_used, created_at, updated_at, version, is_deleted, sync_status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE((SELECT created_at FROM discussions WHERE id = ?), ?), ?, ?, 0, 'DIRTY_LOCAL')
            """.trimIndent()

            db.exec(
                sql,
                listOf(
                    discussion.id,
                    profileId,
                    discussion.projectId,
                    discussion.name,
                    discussion.status.name,
                    configJson,
                    discussion.conclusion,
                    discussion.handoffPrompt,
                    discussion.totalTokensUsed,
                    discussion.id,
                    now,
                    now,
                    nextVersion
                )
            )

            // Save messages
            for (msg in discussion.transcript) {
                saveMessage(discussion.id, msg)
            }
        }

        refreshDiscussionsFlow(profileId)
        return discussion
    }

    fun deleteDiscussion(profileId: String, id: String) {
        val now = System.currentTimeMillis()
        val sql = "UPDATE discussions SET is_deleted = 1, updated_at = ?, sync_status = 'DIRTY_LOCAL' WHERE profile_id = ? AND id = ?"
        db.exec(sql, listOf(now, profileId, id))
        refreshDiscussionsFlow(profileId)
    }

    fun observeDiscussions(profileId: String): Flow<List<Discussion>> {
        refreshDiscussionsFlow(profileId)
        return discussionsFlow.map { map ->
            map[profileId] ?: emptyList()
        }
    }

    private fun refreshDiscussionsFlow(profileId: String) {
        val current = discussionsFlow.value.toMutableMap()
        current[profileId] = getDiscussions(profileId)
        discussionsFlow.value = current
    }

    // ── Messages ──────────────────────────────────────────────────────────────

    private fun getMessages(discussionId: String): List<DebateMessage> {
        val sql = """
            SELECT agent_id, round, content, is_error, command_json, tokens_in, tokens_out, created_at
            FROM debate_messages
            WHERE discussion_id = ? AND is_deleted = 0
            ORDER BY round ASC, created_at ASC
        """.trimIndent()

        return db.query(sql, listOf(discussionId)) { cursor ->
            val agentStr = cursor.getString(0) ?: Provider.ANTHROPIC.name
            val agentId = runCatching { Provider.valueOf(agentStr) }.getOrDefault(Provider.ANTHROPIC)
            val round = cursor.getInt(1)
            val content = cursor.getString(2) ?: ""
            val isError = cursor.getInt(3) == 1
            val commandJson = cursor.getString(4)
            val commandRequest = commandJson?.let {
                runCatching { json.decodeFromString<com.dialex.model.CommandRequest>(it) }.getOrNull()
            }
            val tokensIn = if (cursor.isNull(5)) null else cursor.getInt(5)
            val tokensOut = if (cursor.isNull(6)) null else cursor.getInt(6)
            val timestampMs = if (cursor.isNull(7)) 0L else cursor.getLong(7)

            DebateMessage(
                agentId = agentId,
                round = round,
                content = content,
                isError = isError,
                commandRequest = commandRequest,
                tokensIn = tokensIn,
                tokensOut = tokensOut,
                timestampMs = timestampMs
            )
        }
    }

    private fun saveMessage(discussionId: String, msg: DebateMessage) {
        val now = System.currentTimeMillis()
        val ts = if (msg.timestampMs > 0L) msg.timestampMs else now
        val msgId = "${discussionId}_${msg.round}_${msg.agentId.name}"
        val cmdJson = msg.commandRequest?.let { json.encodeToString(it) }

        val sql = """
            INSERT OR REPLACE INTO debate_messages (
                id, discussion_id, agent_id, round, content, is_error, command_json,
                tokens_in, tokens_out, created_at, version, is_deleted, sync_status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE((SELECT created_at FROM debate_messages WHERE id = ?), ?), 1, 0, 'DIRTY_LOCAL')
        """.trimIndent()

        db.exec(
            sql,
            listOf(
                msgId,
                discussionId,
                msg.agentId.name,
                msg.round,
                msg.content,
                if (msg.isError) 1 else 0,
                cmdJson,
                msg.tokensIn,
                msg.tokensOut,
                msgId,
                ts
            )
        )
        db.exec("UPDATE discussions SET updated_at = ? WHERE id = ?", listOf(now, discussionId))
    }

    // ── Sync Foundation ───────────────────────────────────────────────────────

    fun getDirtyEntities(profileId: String): List<SyncMetadata> {
        val results = mutableListOf<SyncMetadata>()

        // Dirty projects
        val projSql = "SELECT id, version, updated_at, is_deleted FROM projects WHERE profile_id = ? AND sync_status = 'DIRTY_LOCAL'"
        val dirtyProjects = db.query(projSql, listOf(profileId)) { cursor ->
            SyncMetadata(
                entityId = cursor.getString(0) ?: "",
                profileId = profileId,
                version = cursor.getLong(1),
                updatedAt = cursor.getLong(2),
                isDeleted = cursor.getInt(3) == 1,
                syncStatus = SyncStatus.DIRTY_LOCAL
            )
        }
        results.addAll(dirtyProjects)

        // Dirty discussions
        val discSql = "SELECT id, version, updated_at, is_deleted FROM discussions WHERE profile_id = ? AND sync_status = 'DIRTY_LOCAL'"
        val dirtyDiscussions = db.query(discSql, listOf(profileId)) { cursor ->
            SyncMetadata(
                entityId = cursor.getString(0) ?: "",
                profileId = profileId,
                version = cursor.getLong(1),
                updatedAt = cursor.getLong(2),
                isDeleted = cursor.getInt(3) == 1,
                syncStatus = SyncStatus.DIRTY_LOCAL
            )
        }
        results.addAll(dirtyDiscussions)

        return results
    }

    fun markSynced(profileId: String, entityId: String, version: Long, updatedAt: Long) {
        db.exec(
            "UPDATE projects SET sync_status = 'SYNCED', version = ?, updated_at = ? WHERE profile_id = ? AND id = ?",
            listOf(version, updatedAt, profileId, entityId)
        )
        db.exec(
            "UPDATE discussions SET sync_status = 'SYNCED', version = ?, updated_at = ? WHERE profile_id = ? AND id = ?",
            listOf(version, updatedAt, profileId, entityId)
        )
    }

    private fun mapDiscussion(cursor: SqlCursor): Discussion {
        val id = cursor.getString(0) ?: ""
        val projectId = cursor.getString(1) ?: ""
        val name = cursor.getString(2) ?: ""
        val statusStr = cursor.getString(3) ?: DiscussionStatus.DRAFT.name
        val status = runCatching { DiscussionStatus.valueOf(statusStr) }.getOrDefault(DiscussionStatus.DRAFT)
        val configJson = cursor.getString(4) ?: "{}"
        val config = runCatching { json.decodeFromString<DebateConfig>(configJson) }.getOrElse {
            DebateConfig(
                topic = name,
                primary = com.dialex.model.Agent(
                    provider = Provider.ANTHROPIC,
                    model = Provider.ANTHROPIC.defaultModel()
                )
            )
        }
        val conclusion = cursor.getString(5)
        val handoffPrompt = cursor.getString(6)
        val totalTokens = cursor.getLong(7)

        return Discussion(
            id = id,
            projectId = projectId,
            name = name,
            config = config,
            status = status,
            conclusion = conclusion,
            handoffPrompt = handoffPrompt,
            totalTokensUsed = totalTokens
        )
    }
}
