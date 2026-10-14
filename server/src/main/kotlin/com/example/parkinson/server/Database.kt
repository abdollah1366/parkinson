package com.example.parkinson.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.flywaydb.core.Flyway
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource

object Database {
    fun connect(url: String, user: String, password: String, poolSize: Int = 10): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = url
                username = user
                this.password = password
                maximumPoolSize = poolSize
                isAutoCommit = true
            },
        )

    /**
     * Starts a PostgreSQL that keeps its files in [dataDir] (kept between runs) and stops with the server; returns the
     * JDBC URL of its "parkinson" database. For testing on a PC only: it listens on 127.0.0.1 and has no password.
     * The port is a free one picked on each start; a fixed port can fall in a range Windows reserves (bind fails).
     */
    fun startEmbedded(dataDir: String): String {
        val postgres = EmbeddedPostgres.builder()
            .setDataDirectory(Path.of(dataDir).toAbsolutePath())
            .setCleanDataDirectory(false)
            .start()
        Runtime.getRuntime().addShutdownHook(Thread { postgres.close() })
        return postgres.getJdbcUrl("postgres", "parkinson")
    }

    /**
     * Creates the database named in [url] when it does not exist yet (a fresh PostgreSQL on a test computer), by
     * connecting to the server's default "postgres" database. [user] needs the right to create databases.
     */
    fun createIfMissing(url: String, user: String, password: String) {
        val name = url.substringAfterLast('/').substringBefore('?')
        require(Regex("[A-Za-z_][A-Za-z0-9_]*").matches(name)) { "unexpected database name in DATABASE_URL" }
        try {
            DriverManager.getConnection(url, user, password).close()
        } catch (e: SQLException) {
            if (e.sqlState != "3D000") throw e // 3D000: the database does not exist.
            val admin = url.substringBeforeLast('/') + "/postgres"
            DriverManager.getConnection(admin, user, password).use { c ->
                c.createStatement().use { it.execute("CREATE DATABASE $name") }
            }
        }
    }

    /** Brings the schema up to date (src/main/resources/db/migration). */
    fun migrate(dataSource: DataSource) {
        Flyway.configure().dataSource(dataSource).load().migrate()
    }
}

/** Plain JDBC with one transaction per call. */
class Db(private val dataSource: DataSource) {
    fun <T> tx(block: Connection.() -> T): T = dataSource.connection.use { c ->
        c.autoCommit = false
        try {
            c.block().also { c.commit() }
        } catch (e: Throwable) {
            c.rollback()
            throw e
        }
    }
}

fun Connection.update(sql: String, vararg args: Any?): Int =
    prepareStatement(sql).use { it.bind(args); it.executeUpdate() }

fun <T> Connection.query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { st ->
        st.bind(args)
        st.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
    }

fun <T> Connection.single(sql: String, vararg args: Any?, map: (ResultSet) -> T): T? =
    query(sql, *args, map = map).firstOrNull()

private fun PreparedStatement.bind(args: Array<out Any?>) {
    args.forEachIndexed { i, a ->
        when (a) {
            is Instant -> setObject(i + 1, OffsetDateTime.ofInstant(a, ZoneOffset.UTC))
            else -> setObject(i + 1, a)
        }
    }
}

fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()

fun ResultSet.epochMs(column: String): Long? = instant(column)?.toEpochMilli()

fun ResultSet.intOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }

fun ResultSet.booleanOrNull(column: String): Boolean? = getBoolean(column).takeUnless { wasNull() }
