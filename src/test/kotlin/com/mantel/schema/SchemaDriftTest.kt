package com.mantel.schema

import com.mantel.allTables
import com.mantel.kernel.DatabaseConfig
import com.mantel.kernel.Schema
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Flyway owns the schema and Exposed declares the columns the code reads. Nothing keeps the two
 * honest except this test: it migrates an empty database and asserts Exposed needs no further
 * statement to reach the schema it expects.
 */
@Testcontainers
class SchemaDriftTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }

    @Test
    fun `the migrations build exactly the schema the code declares`() {
        val dataSource =
            Schema.dataSource(
                DatabaseConfig(postgres.jdbcUrl, postgres.username, postgres.password),
            )
        Schema.migrate(dataSource)
        val database = Schema.connect(dataSource)

        // statementsRequiredToActualizeScheme is deprecated in favour of exposed-migration's
        // MigrationUtils, which sits in the default package and cannot be imported from a packaged
        // file. Move when Exposed gives it a package.
        @Suppress("DEPRECATION")
        val drift =
            transaction(database) {
                SchemaUtils.statementsRequiredToActualizeScheme(*allTables.toTypedArray())
            }

        assertTrue(drift.isEmpty()) {
            "Exposed and the migrations disagree. Missing from db/migration:\n" + drift.joinToString("\n")
        }
    }
}
