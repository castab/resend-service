package com.castab.resend.support

import com.castab.resend.dataSource
import com.castab.resend.migrate
import com.zaxxer.hikari.HikariDataSource

/**
 * Shared disposable PostgreSQL for integration specs, gated on `TEST_DATABASE_URL`.
 * Runs migrations once, then truncates application tables between tests.
 */
object TestDb {
    val url: String? = System.getenv("TEST_DATABASE_URL")
    val enabled: Boolean get() = !url.isNullOrBlank()

    private val tables = listOf(
        "email_outbox_entries",
        "email_outbox_batches",
        "email_messages",
        "email_conversations",
        "resend_wh_emails",
        "resend_wh_contacts",
        "resend_wh_domains",
    )

    val source: HikariDataSource by lazy {
        dataSource(url!!).also { migrate(it) }
    }

    fun truncate() {
        source.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("TRUNCATE ${tables.joinToString(", ")} RESTART IDENTITY CASCADE")
            }
        }
    }
}
