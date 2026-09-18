package com.mantel.kernel

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import javax.sql.DataSource

/**
 * Flyway owns the schema. Exposed only reads it: the Table objects in each feature declare the
 * columns the code uses and never create them. ConventionTest asserts the two agree.
 */
object Schema {
    fun dataSource(config: DatabaseConfig): DataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.url
                username = config.user
                password = config.password
                maximumPoolSize = 10
                isAutoCommit = false
            },
        )

    fun migrate(dataSource: DataSource) {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .validateMigrationNaming(true)
            .load()
            .migrate()
    }

    fun connect(dataSource: DataSource): Database = Database.connect(dataSource)
}
