package com.example.parkinson.server

import org.postgresql.PGConnection
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.sql.DataSource
import kotlin.io.path.deleteIfExists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Backups of the whole database as one zip file a day (every table as CSV), made by the server itself, so a computer
 * without the PostgreSQL tools (the built-in database on Windows) is backed up too. Old files are deleted after
 * [keepDays] days. Copy the folder to another computer or disk now and then: a backup on the same disk does not survive
 * the disk.
 */
class Backups(private val dataSource: DataSource, private val dir: Path, private val keepDays: Int, private val zone: ZoneId) {
    private val log = LoggerFactory.getLogger("backups")

    /** Writes a backup now; returns its file. */
    fun backupNow(now: Instant = Instant.now()): Path {
        Files.createDirectories(dir)
        val name = "parkinson-" + DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss").format(now.atZone(zone)) + ".zip"
        val partial = dir.resolve("$name.part")
        dataSource.connection.use { c ->
            // One snapshot for all tables: a backup taken while people use the panel is still consistent.
            c.autoCommit = false
            c.transactionIsolation = java.sql.Connection.TRANSACTION_REPEATABLE_READ
            c.createStatement().use { it.execute("SET TRANSACTION READ ONLY") }
            val copy = c.unwrap(PGConnection::class.java).copyAPI
            ZipOutputStream(Files.newOutputStream(partial)).use { zip ->
                val tables = tables(c)
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write((listOf("format=1", "migration=" + migration(c)) + tables.map { "table=$it" }).joinToString("\n").toByteArray())
                zip.closeEntry()
                for (t in tables) {
                    zip.putNextEntry(ZipEntry("$t.csv"))
                    copy.copyOut("COPY \"$t\" TO STDOUT WITH (FORMAT csv, HEADER true)", zip)
                    zip.closeEntry()
                }
            }
            c.rollback()
        }
        val file = dir.resolve(name)
        Files.move(partial, file)
        prune(now)
        return file
    }

    /** Deletes backups older than [keepDays], always keeping the newest few. */
    private fun prune(now: Instant) {
        val files = dir.listDirectoryEntries("parkinson-*.zip").sortedByDescending { it.getLastModifiedTime().toInstant() }
        files.drop(KEEP_AT_LEAST).filter { it.getLastModifiedTime().toInstant() < now.minus(Duration.ofDays(keepDays.toLong())) }
            .forEach { it.deleteIfExists() }
    }

    /** Makes a backup every night at [at] (the clinic's time), on a background thread. */
    fun startDaily(at: LocalTime = LocalTime.of(3, 0)) {
        Thread({
            while (true) {
                val now = Instant.now()
                var next = now.atZone(zone).toLocalDate().atTime(at).atZone(zone).toInstant()
                if (!next.isAfter(now)) next = next.plus(Duration.ofDays(1))
                try {
                    Thread.sleep(Duration.between(now, next).toMillis())
                } catch (e: InterruptedException) {
                    return@Thread
                }
                try {
                    log.info("Backup written: {}", backupNow().name)
                } catch (e: Exception) {
                    log.error("Backup failed", e)
                }
            }
        }, "daily-backup").apply { isDaemon = true }.start()
    }

    companion object {
        const val MANIFEST = "manifest.txt"
        const val KEEP_AT_LEAST = 3

        private fun tables(c: java.sql.Connection): List<String> = c.createStatement().use { s ->
            s.executeQuery(
                """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history'
                ORDER BY table_name
                """.trimIndent()
            ).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }

        private fun migration(c: java.sql.Connection): String = runCatching {
            c.createStatement().use { s ->
                s.executeQuery("SELECT max(installed_rank) FROM flyway_schema_history WHERE success").use { rs -> if (rs.next()) rs.getString(1) else "" }
            }
        }.getOrDefault("")

        /**
         * Restores [zip] into this database, which must be up to date (migrated) and empty: nothing is overwritten.
         * Returns how many tables were filled.
         */
        fun restore(dataSource: DataSource, zip: Path): Int {
            val entries = linkedMapOf<String, ByteArray>()
            ZipInputStream(Files.newInputStream(zip)).use { input ->
                while (true) {
                    val e = input.nextEntry ?: break
                    val out = ByteArrayOutputStream()
                    input.copyTo(out)
                    entries[e.name] = out.toByteArray()
                }
            }
            val manifest = entries[MANIFEST]?.let { String(it) } ?: error("not a backup of this server: $zip")
            val tables = manifest.lines().filter { it.startsWith("table=") }.map { it.removePrefix("table=") }
            dataSource.connection.use { c ->
                c.autoCommit = false
                val known = tables(c).toSet()
                val unknown = tables - known
                require(unknown.isEmpty()) { "the backup has tables this server does not: $unknown" }
                for (t in tables) {
                    val rows = c.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM \"$t\"").use { it.next(); it.getLong(1) } }
                    require(rows == 0L) { "the database is not empty (table $t); restore only into a new database" }
                }
                // Foreign keys are checked by the data's own order otherwise; the backup is consistent as a whole.
                c.createStatement().use { it.execute("SET session_replication_role = replica") }
                val copy = c.unwrap(PGConnection::class.java).copyAPI
                for (t in tables) {
                    val data = entries["$t.csv"] ?: continue
                    copy.copyIn("COPY \"$t\" FROM STDIN WITH (FORMAT csv, HEADER true)", ByteArrayInputStream(data))
                }
                c.createStatement().use { it.execute("SET session_replication_role = DEFAULT") }
                // Ids continue after the restored rows.
                c.createStatement().use { s ->
                    s.executeQuery(
                        """
                        SELECT table_name, column_name FROM information_schema.columns
                        WHERE table_schema = 'public' AND column_default LIKE 'nextval(%'
                        """.trimIndent()
                    ).use { rs -> buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) } }
                }.forEach { (t, col) ->
                    c.createStatement().use {
                        it.execute("SELECT setval(pg_get_serial_sequence('\"$t\"', '$col'), coalesce((SELECT max(\"$col\") FROM \"$t\"), 0) + 1, false)")
                    }
                }
                c.commit()
            }
            return tables.size
        }
    }
}
