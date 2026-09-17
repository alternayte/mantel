package com.mantel.schema

import com.mantel.allTables
import com.mantel.support.TestDatabase
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Flyway owns the schema and Exposed declares the columns the code reads. Nothing keeps the two
 * honest except this test: it reads the migrated database and asserts Exposed needs no further
 * statement to reach the schema it expects.
 *
 * It shares the one migrated Postgres the rest of the suite uses. Connecting a second database
 * would make it the Exposed default, and every later test would then talk to a container that
 * stops when this class finishes.
 */
class SchemaDriftTest {
    @Test
    fun `the migrations build exactly the schema the code declares`() {
        TestDatabase.truncate()

        // statementsRequiredToActualizeScheme is deprecated in favour of exposed-migration's
        // MigrationUtils, which sits in the default package and cannot be imported from a packaged
        // file. Move when Exposed gives it a package.
        @Suppress("DEPRECATION")
        val drift = transaction { SchemaUtils.statementsRequiredToActualizeScheme(*allTables.toTypedArray()) }

        assertTrue(drift.isEmpty()) {
            "Exposed and the migrations disagree. Missing from db/migration:\n" + drift.joinToString("\n")
        }
    }
}
