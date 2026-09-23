package com.dialex.data.db

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement

class DesktopSqlDatabase(private val connection: Connection) : SqlDatabase {
    override fun exec(sql: String, args: List<Any?>) {
        connection.prepareStatement(sql).use { stmt ->
            bindArgs(stmt, args)
            stmt.executeUpdate()
        }
    }

    override fun <T> query(sql: String, args: List<Any?>, mapper: (SqlCursor) -> T): List<T> {
        val stmt = connection.prepareStatement(sql)
        bindArgs(stmt, args)
        val rs = stmt.executeQuery()
        val results = mutableListOf<T>()
        val cursor = object : SqlCursor {
            override fun next(): Boolean = rs.next()
            override fun getString(columnIndex: Int): String? = rs.getString(columnIndex + 1)
            override fun getLong(columnIndex: Int): Long = rs.getLong(columnIndex + 1)
            override fun getInt(columnIndex: Int): Int = rs.getInt(columnIndex + 1)
            override fun getDouble(columnIndex: Int): Double = rs.getDouble(columnIndex + 1)
            override fun isNull(columnIndex: Int): Boolean = rs.getObject(columnIndex + 1) == null
            override fun close() {
                rs.close()
                stmt.close()
            }
        }
        cursor.use {
            while (cursor.next()) {
                results.add(mapper(cursor))
            }
        }
        return results
    }

    override fun transaction(block: () -> Unit) {
        val oldAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            block()
            connection.commit()
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = oldAutoCommit
        }
    }

    override fun close() {
        connection.close()
    }

    private fun bindArgs(stmt: PreparedStatement, args: List<Any?>) {
        for (i in args.indices) {
            val arg = args[i]
            val paramIndex = i + 1
            when (arg) {
                null -> stmt.setNull(paramIndex, java.sql.Types.NULL)
                is String -> stmt.setString(paramIndex, arg)
                is Int -> stmt.setInt(paramIndex, arg)
                is Long -> stmt.setLong(paramIndex, arg)
                is Double -> stmt.setDouble(paramIndex, arg)
                is Float -> stmt.setFloat(paramIndex, arg)
                is Boolean -> stmt.setInt(paramIndex, if (arg) 1 else 0)
                is ByteArray -> stmt.setBytes(paramIndex, arg)
                else -> stmt.setString(paramIndex, arg.toString())
            }
        }
    }
}

actual fun createPlatformDatabase(dbPath: String): SqlDatabase {
    val file = File(dbPath)
    file.parentFile?.mkdirs()
    val conn = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
    val db = DesktopSqlDatabase(conn)
    DatabaseSchema.createTables(db)
    return db
}

actual fun defaultPlatformDatabase(): SqlDatabase {
    val home = System.getProperty("user.home") ?: "."
    val dir = File(home, ".dialex")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    val path = File(dir, "dialex.db").absolutePath
    return createPlatformDatabase(path)
}
