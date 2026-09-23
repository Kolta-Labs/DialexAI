package com.dialex.data.db

expect fun createPlatformDatabase(dbPath: String): SqlDatabase
expect fun defaultPlatformDatabase(): SqlDatabase
