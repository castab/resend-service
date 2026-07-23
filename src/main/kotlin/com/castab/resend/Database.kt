package com.castab.resend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.Location
import java.io.InputStream
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource

fun dataSource(url: String): HikariDataSource {
    val uri = URI(url)
    val credentials = uri.userInfo?.split(":", limit = 2).orEmpty()
    return HikariDataSource(HikariConfig().apply {
        jdbcUrl = "jdbc:postgresql://${uri.host}:${if (uri.port < 0) 5432 else uri.port}${uri.path}${uri.query?.let { "?$it" }.orEmpty()}"
        username = credentials.getOrNull(0)
        password = credentials.getOrNull(1)
        maximumPoolSize = 10
        minimumIdle = 1
        connectionTimeout = 3_000
    })
}

private val migrationLocation = Location("classpath:db/migration")
private const val migrationIndexPath = "db/migration/index.txt"
private const val migrationRoot = "db/migration"

fun migrate(source: DataSource) {
    val classLoader = Thread.currentThread().contextClassLoader ?: Config::class.java.classLoader
    val locations = if (classLoader.getResource(migrationRoot)?.protocol == "resource") {
        arrayOf(nativeMigrationLocation(classLoader))
    } else {
        arrayOf(migrationLocation)
    }

    Flyway.configure()
        .dataSource(source)
        .locations(*locations)
        .load()
        .migrate()
}

private fun nativeMigrationLocation(classLoader: ClassLoader): Location {
    val directory = Files.createTempDirectory("flyway-migrations-")
    loadMigrationPaths(classLoader).forEach { relativePath ->
        copyMigrationResource(classLoader, relativePath, directory.resolve(relativePath))
    }
    directory.toFile().deleteOnExit()
    return Location("${Location.FILESYSTEM_PREFIX}${directory.toAbsolutePath().toString().replace('\\', '/')}")
}

private fun loadMigrationPaths(classLoader: ClassLoader): List<String> {
    val index = requireNotNull(classLoader.getResourceAsStream(migrationIndexPath)) {
        "Missing Flyway migration index: $migrationIndexPath"
    }
    return index.bufferedReader(UTF_8).useLines { lines ->
        lines.map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
    }
}

private fun copyMigrationResource(classLoader: ClassLoader, relativePath: String, destination: Path) {
    val absolutePath = "$migrationRoot/$relativePath"
    val input = requireNotNull(classLoader.getResourceAsStream(absolutePath)) {
        "Missing Flyway migration resource: $absolutePath"
    }
    input.use {
        Files.createDirectories(destination.parent)
        Files.newOutputStream(destination).use(it::transferTo)
    }
}

