package com.mantel.support

import com.mantel.allTables
import com.mantel.http.Services
import com.mantel.http.module
import com.mantel.kernel.Config
import com.mantel.kernel.DatabaseConfig
import com.mantel.kernel.GitHubConfig
import com.mantel.kernel.Mail
import com.mantel.kernel.Mailer
import com.mantel.kernel.Schema
import com.mantel.kernel.StorageConfig
import com.mantel.storage.ObjectStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.transactions.transaction
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Duration

/**
 * One Postgres for the whole test run, migrated once and truncated between tests. Testcontainers,
 * not a mock: the claim loop, the partial unique indexes and the timestamp types are the parts most
 * likely to be wrong, and none of them exist in a fake.
 */
object TestDatabase {
    private val container =
        PostgreSQLContainer("postgres:17-alpine").apply {
            withReuse(false)
            start()
        }

    val config = DatabaseConfig(container.jdbcUrl, container.username, container.password)

    init {
        val dataSource = Schema.dataSource(config)
        Schema.migrate(dataSource)
        Schema.connect(dataSource)
    }

    fun truncate() {
        transaction {
            val names = allTables.joinToString(", ") { it.tableName }
            exec("TRUNCATE $names RESTART IDENTITY CASCADE")
        }
    }
}

/** Every mail the application sent, in order. */
class RecordingMailer : Mailer {
    val sent = mutableListOf<Mail>()

    override fun send(mail: Mail) {
        sent += mail
    }

    fun lastLink(): String = sent.last().body.lines().first { it.startsWith("http") }
}

class RecordingStorage : ObjectStorage {
    val objects = mutableMapOf<String, String>()
    val deletedPrefixes = mutableListOf<String>()

    override fun presignPut(
        key: String,
        contentType: String,
        contentLength: Long,
        expiresIn: Duration,
    ) = "https://storage.test/$key"

    override fun delete(keys: List<String>) {
        keys.forEach { objects.remove(it) }
    }

    override fun deletePrefix(prefix: String) {
        deletedPrefixes += prefix
        objects.keys.filter { it.startsWith(prefix) }.forEach { objects.remove(it) }
    }
}

fun testConfig(github: GitHubConfig? = null) =
    Config(
        port = 0,
        publicBaseUrl = "http://localhost",
        defaultQuotaBytes = 10L * 1024 * 1024 * 1024,
        database = TestDatabase.config,
        storage =
            StorageConfig(
                endpoint = "http://storage.test",
                region = "auto",
                bucket = "mantel",
                accessKeyId = "test",
                secretAccessKey = "test",
                forcePathStyle = true,
            ),
        smtp = null,
        github = github,
    )

class Harness(
    val mailer: RecordingMailer,
    val storage: RecordingStorage,
)

/**
 * Boots the real routing over the real database with a recording mailer and storage. `github`
 * supplies a mock GitHub; without one the OAuth routes report that GitHub is not configured.
 */
fun withApp(
    github: GitHubConfig? = null,
    githubResponder: MockEngine? = null,
    block: suspend ApplicationTestBuilder.(Harness) -> Unit,
) {
    TestDatabase.truncate()
    val harness = Harness(RecordingMailer(), RecordingStorage())
    testApplication {
        val client =
            githubResponder?.let { engine ->
                HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            } ?: HttpClient(
                MockEngine {
                    respond(
                        "{}",
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                },
            ) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
        application {
            module(
                Services(
                    config = testConfig(github),
                    storage = harness.storage,
                    mailer = harness.mailer,
                    httpClient = client,
                ),
            )
        }
        block(harness)
    }
}

/** A client that keeps cookies, which is how a browser behaves and how a session is exercised. */
fun ApplicationTestBuilder.browser() =
    createClient {
        install(HttpCookies)
        install(ContentNegotiation) { json() }
        followRedirects = false
    }
