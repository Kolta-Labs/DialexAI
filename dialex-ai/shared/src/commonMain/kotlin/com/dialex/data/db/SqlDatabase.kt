package com.dialex.data.db

interface SqlCursor : AutoCloseable {
    fun next(): Boolean
    fun getString(columnIndex: Int): String?
    fun getLong(columnIndex: Int): Long
    fun getInt(columnIndex: Int): Int
    fun getDouble(columnIndex: Int): Double
    fun isNull(columnIndex: Int): Boolean
}

interface SqlDatabase : AutoCloseable {
    fun exec(sql: String, args: List<Any?> = emptyList())
    fun <T> query(sql: String, args: List<Any?> = emptyList(), mapper: (SqlCursor) -> T): List<T>
    fun <T> querySingle(sql: String, args: List<Any?> = emptyList(), mapper: (SqlCursor) -> T): T? {
        val list = query(sql, args, mapper)
        return list.firstOrNull()
    }
    fun transaction(block: () -> Unit)
}
