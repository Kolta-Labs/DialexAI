package com.dialex.data.db

object DatabaseSchema {
    fun createTables(db: SqlDatabase) {
        db.exec("""
            CREATE TABLE IF NOT EXISTS profiles (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                type TEXT NOT NULL,
                remote_url TEXT,
                remote_username TEXT,
                lock_mode TEXT NOT NULL,
                lock_data TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                version INTEGER NOT NULL DEFAULT 1,
                is_deleted INTEGER NOT NULL DEFAULT 0,
                sync_status TEXT NOT NULL DEFAULT 'SYNCED'
            )
        """.trimIndent())

        db.exec("""
            CREATE TABLE IF NOT EXISTS projects (
                id TEXT PRIMARY KEY,
                profile_id TEXT NOT NULL,
                name TEXT NOT NULL,
                shared_context TEXT NOT NULL DEFAULT '',
                shared_instructions TEXT NOT NULL DEFAULT '',
                default_consensus REAL NOT NULL DEFAULT 1.0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                version INTEGER NOT NULL DEFAULT 1,
                is_deleted INTEGER NOT NULL DEFAULT 0,
                sync_status TEXT NOT NULL DEFAULT 'SYNCED'
            )
        """.trimIndent())

        db.exec("CREATE INDEX IF NOT EXISTS idx_projects_profile ON projects(profile_id)")

        db.exec("""
            CREATE TABLE IF NOT EXISTS discussions (
                id TEXT PRIMARY KEY,
                profile_id TEXT NOT NULL,
                project_id TEXT NOT NULL,
                name TEXT NOT NULL,
                status TEXT NOT NULL,
                config_json TEXT NOT NULL,
                conclusion TEXT,
                handoff_prompt TEXT,
                total_tokens_used INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                version INTEGER NOT NULL DEFAULT 1,
                is_deleted INTEGER NOT NULL DEFAULT 0,
                sync_status TEXT NOT NULL DEFAULT 'SYNCED'
            )
        """.trimIndent())

        db.exec("CREATE INDEX IF NOT EXISTS idx_discussions_profile ON discussions(profile_id)")
        db.exec("CREATE INDEX IF NOT EXISTS idx_discussions_project ON discussions(project_id)")

        db.exec("""
            CREATE TABLE IF NOT EXISTS debate_messages (
                id TEXT PRIMARY KEY,
                discussion_id TEXT NOT NULL,
                agent_id TEXT NOT NULL,
                round INTEGER NOT NULL,
                content TEXT NOT NULL,
                is_error INTEGER NOT NULL DEFAULT 0,
                command_json TEXT,
                tokens_in INTEGER,
                tokens_out INTEGER,
                created_at INTEGER NOT NULL,
                version INTEGER NOT NULL DEFAULT 1,
                is_deleted INTEGER NOT NULL DEFAULT 0,
                sync_status TEXT NOT NULL DEFAULT 'SYNCED'
            )
        """.trimIndent())

        db.exec("CREATE INDEX IF NOT EXISTS idx_messages_discussion ON debate_messages(discussion_id)")

        db.exec("""
            CREATE TABLE IF NOT EXISTS api_keys_secure (
                profile_id TEXT NOT NULL,
                provider TEXT NOT NULL,
                ciphertext TEXT NOT NULL,
                iv TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (profile_id, provider)
            )
        """.trimIndent())

        db.exec("""
            CREATE TABLE IF NOT EXISTS active_profile_pointer (
                key TEXT PRIMARY KEY,
                active_profile_id TEXT NOT NULL
            )
        """.trimIndent())

        db.exec("""
            CREATE TABLE IF NOT EXISTS sync_cursors (
                profile_id TEXT NOT NULL,
                device_id TEXT NOT NULL,
                last_synced_at INTEGER NOT NULL,
                cursor_version INTEGER NOT NULL,
                PRIMARY KEY (profile_id, device_id)
            )
        """.trimIndent())

        db.exec("""
            CREATE TABLE IF NOT EXISTS council_templates (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                subtitle TEXT NOT NULL,
                template_json TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                is_deleted INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
    }
}
