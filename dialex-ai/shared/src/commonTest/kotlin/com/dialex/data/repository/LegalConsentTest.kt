package com.dialex.data.repository

import com.dialex.data.datasource.LegalConsentLocalDataSource
import com.dialex.data.db.SqlCursor
import com.dialex.data.db.SqlDatabase
import com.dialex.domain.model.LegalConsent
import com.dialex.domain.usecase.GetLegalConsentUseCase
import com.dialex.domain.usecase.RecordLegalConsentUseCase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegalConsentTest {

    private class FakeSqlDatabase : SqlDatabase {
        val storage = mutableMapOf<String, MutableList<Any?>>()

        override fun exec(sql: String, args: List<Any?>) {
            if (sql.contains("INSERT OR REPLACE INTO legal_consent", ignoreCase = true)) {
                val id = args[0] as String
                storage[id] = args.toMutableList()
            }
        }

        override fun <T> query(sql: String, args: List<Any?>, mapper: (SqlCursor) -> T): List<T> {
            val id = args.firstOrNull() as? String ?: LegalConsent.DEFAULT_ID
            val record = storage[id] ?: return emptyList()

            val cursor = object : SqlCursor {
                private var read = false
                override fun next(): Boolean {
                    return if (!read) {
                        read = true
                        true
                    } else {
                        false
                    }
                }

                override fun getString(columnIndex: Int): String? = record.getOrNull(columnIndex + 1) as? String
                override fun getLong(columnIndex: Int): Long = (record.getOrNull(columnIndex + 1) as? Number)?.toLong() ?: 0L
                override fun getInt(columnIndex: Int): Int = (record.getOrNull(columnIndex + 1) as? Number)?.toInt() ?: 0
                override fun getDouble(columnIndex: Int): Double = (record.getOrNull(columnIndex + 1) as? Number)?.toDouble() ?: 0.0
                override fun isNull(columnIndex: Int): Boolean = record.getOrNull(columnIndex + 1) == null
                override fun close() {}
            }

            return if (cursor.next()) listOf(mapper(cursor)) else emptyList()
        }

        override fun transaction(block: () -> Unit) {
            block()
        }

        override fun close() {}
    }

    @Test
    fun initial_state_requires_legal_consent() = runTest {
        val fakeDb = FakeSqlDatabase()
        val dataSource = LegalConsentLocalDataSource(fakeDb)
        val repository = LegalConsentRepositoryImpl(dataSource)
        val getUseCase = GetLegalConsentUseCase(repository)

        val consent = getUseCase()
        assertFalse(consent.isAccepted)
        assertTrue(getUseCase.isConsentRequired())
    }

    @Test
    fun recording_consent_persists_version_and_satisfies_requirement() = runTest {
        val fakeDb = FakeSqlDatabase()
        val dataSource = LegalConsentLocalDataSource(fakeDb)
        val repository = LegalConsentRepositoryImpl(dataSource)
        val getUseCase = GetLegalConsentUseCase(repository)
        val recordUseCase = RecordLegalConsentUseCase(repository)

        val testTime = 1774500000000L
        recordUseCase(
            termsVersion = "1.0.0",
            privacyVersion = "1.0.0",
            timestampMs = testTime
        )

        val updated = getUseCase()
        assertTrue(updated.isAccepted)
        assertEquals("1.0.0", updated.termsVersion)
        assertEquals("1.0.0", updated.privacyVersion)
        assertEquals(testTime, updated.acceptedAtTimestamp)
        assertFalse(getUseCase.isConsentRequired("1.0.0"))
    }

    @Test
    fun bumped_terms_version_requires_reconsent() = runTest {
        val fakeDb = FakeSqlDatabase()
        val dataSource = LegalConsentLocalDataSource(fakeDb)
        val repository = LegalConsentRepositoryImpl(dataSource)
        val getUseCase = GetLegalConsentUseCase(repository)
        val recordUseCase = RecordLegalConsentUseCase(repository)

        recordUseCase(termsVersion = "1.0.0", privacyVersion = "1.0.0")
        assertFalse(getUseCase.isConsentRequired("1.0.0"))

        // When a new version is deployed
        assertTrue(getUseCase.isConsentRequired("2.0.0"))
    }
}
