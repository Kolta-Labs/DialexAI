package com.dialex.data.db

import android.database.sqlite.SQLiteDatabase
import java.io.File

class AndroidSqlDatabase(private val db: SQLiteDatabase) : SqlDatabase {
    override fun exec(sql: String, args: List<Any?>) {
        if (args.isEmpty()) {
            db.execSQL(sql)
        } else {
            val bindArgs = args.map { it?.toString() }.toTypedArray()
            db.execSQL(sql, bindArgs)
        }
    }

    override fun <T> query(sql: String, args: List<Any?>, mapper: (SqlCursor) -> T): List<T> {
        val selectionArgs = if (args.isEmpty()) null else args.map { it?.toString() }.toTypedArray()
        val cursor = db.rawQuery(sql, selectionArgs)
        val results = mutableListOf<T>()
        val sqlCursor = object : SqlCursor {
            override fun next(): Boolean = cursor.moveToNext()
            override fun getString(columnIndex: Int): String? = if (cursor.isNull(columnIndex)) null else cursor.getString(columnIndex)
            override fun getLong(columnIndex: Int): Long = cursor.getLong(columnIndex)
            override fun getInt(columnIndex: Int): Int = cursor.getInt(columnIndex)
            override fun getDouble(columnIndex: Int): Double = cursor.getDouble(columnIndex)
            override fun isNull(columnIndex: Int): Boolean = cursor.isNull(columnIndex)
            override fun close() {
                cursor.close()
            }
        }
        sqlCursor.use {
            while (it.next()) {
                results.add(mapper(it))
            }
        }
        return results
    }

    override fun transaction(block: () -> Unit) {
        db.beginTransaction()
        try {
            block()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun close() {
        db.close()
    }
}

actual fun createPlatformDatabase(dbPath: String): SqlDatabase {
    val file = File(dbPath)
    file.parentFile?.mkdirs()
    val sqliteDb = SQLiteDatabase.openOrCreateDatabase(file, null)
    val db = AndroidSqlDatabase(sqliteDb)
    DatabaseSchema.createTables(db)
    return db
}

actual fun defaultPlatformDatabase(): SqlDatabase {
    val path = AndroidContextHolder.getDatabasePath("dialex.db")
    return createPlatformDatabase(path)
}
