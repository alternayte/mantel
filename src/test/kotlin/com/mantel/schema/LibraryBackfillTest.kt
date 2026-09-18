package com.mantel.schema

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * The migration that moves media off albums and onto the account runs once, in production, against
 * rows that already exist. Every other test starts from an empty database and migrates it in one
 * go, so nothing else touches the backfill, and a backfill that is wrong is not recoverable.
 *
 * This one stops at the version before it, puts real rows in, and then finishes.
 */
class LibraryBackfillTest {
    private val container =
        PostgreSQLContainer("postgres:17-alpine").apply { start() }

    private fun flyway(target: String?) =
        Flyway.configure()
            .dataSource(container.jdbcUrl, container.username, container.password)
            .locations("classpath:db/migration")
            .apply { if (target != null) target(org.flywaydb.core.api.MigrationVersion.fromVersion(target)) }
            .load()

    @Test
    fun `media moves to the account and keeps its place and its caption`() {
        flyway("10").migrate()

        val accountId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()

        connection().use { db ->
            db.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO account (id, email, storage_quota_bytes, storage_used_bytes, created_at)
                    VALUES ('$accountId', 'nate@example.com', 100, 30, now());

                    INSERT INTO album (id, account_id, title, status, item_count, total_bytes, created_at, updated_at)
                    VALUES ('$albumId', '$accountId', 'Cornwall', 'ready', 2, 30, now(), now());

                    INSERT INTO media_item
                        (id, album_id, position, kind, original_key, byte_size, caption, status, attempts, created_at)
                    VALUES
                        ('$first', '$albumId', 0, 'photo', 'media/$first/original.jpg', 20, 'low tide', 'ready', 1, now()),
                        ('$second', '$albumId', 1, 'photo', 'media/$second/original.jpg', 10, NULL, 'failed', 3, now());
                    """.trimIndent(),
                )
            }
        }

        flyway(null).migrate()

        connection().use { db ->
            // The photographs belong to the account now.
            db.createStatement().executeQuery(
                "SELECT account_id, status, renderable, content_hash FROM media_item WHERE id = '$first'",
            ).use { rows ->
                rows.next()
                assertEquals(accountId.toString(), rows.getString("account_id"))
                // `ready` meant every derivative exists, which is what `shareable` means now.
                assertEquals("shareable", rows.getString("status"))
                assertEquals(true, rows.getBoolean("renderable"))
                assertEquals(null, rows.getString("content_hash"))
            }

            // A state that was not `ready` is left exactly as it was.
            db.createStatement().executeQuery("SELECT status FROM media_item WHERE id = '$second'").use { rows ->
                rows.next()
                assertEquals("failed", rows.getString("status"))
            }

            // The album keeps its order and its words.
            db.createStatement().executeQuery(
                "SELECT media_item_id, position, caption FROM album_item WHERE album_id = '$albumId' ORDER BY position",
            ).use { rows ->
                rows.next()
                assertEquals(first.toString(), rows.getString("media_item_id"))
                assertEquals(0, rows.getInt("position"))
                assertEquals("low tide", rows.getString("caption"))
                rows.next()
                assertEquals(second.toString(), rows.getString("media_item_id"))
                assertEquals(1, rows.getInt("position"))
                assertEquals(null, rows.getString("caption"))
            }
        }
    }

    private fun connection(): Connection = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
}
