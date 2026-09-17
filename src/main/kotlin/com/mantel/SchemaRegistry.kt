package com.mantel

import org.jetbrains.exposed.sql.Table

/**
 * Every Exposed table the code declares. A feature adds its table here in the milestone that
 * creates it, and SchemaDriftTest asserts this list matches what Flyway actually built.
 * It lives at the composition root because it is the one place allowed to see every feature.
 */
val allTables: List<Table> = emptyList()
