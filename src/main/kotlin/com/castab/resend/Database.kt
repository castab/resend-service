package com.castab.resend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.Location
import org.flywaydb.core.api.ResourceProvider
import org.flywaydb.core.api.resource.LoadableResource
import java.io.Reader
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.io.InputStreamReader
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
    Flyway.configure()
        .dataSource(source)
        .locations(migrationLocation)
        .resourceProvider(IndexBackedResourceProvider(loadMigrationResources(classLoader)))
        .load()
        .migrate()
}

private fun loadMigrationResources(classLoader: ClassLoader): List<MigrationResource> {
    val index = requireNotNull(classLoader.getResourceAsStream(migrationIndexPath)) {
        "Missing Flyway migration index: $migrationIndexPath"
    }
    return index.bufferedReader(UTF_8).useLines { lines ->
        lines.map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { path -> MigrationResource(classLoader, path) }
            .toList()
    }.onEach { resource ->
        require(resource.exists()) { "Missing Flyway migration resource: ${resource.absolutePath}" }
    }
}

private class IndexBackedResourceProvider(private val resources: List<MigrationResource>) : ResourceProvider {
    override fun getResource(name: String): LoadableResource? {
        val normalized = name.trimStart('/')
        return resources.firstOrNull {
            it.absolutePath == normalized || it.relativePath == normalized || it.filename == normalized
        }
    }

    override fun getResources(prefix: String, suffixes: Array<String>): Collection<LoadableResource> {
        val normalizedPrefix = prefix.trimStart('/')
        return resources.filter { resource ->
            resource.absolutePath.startsWith(normalizedPrefix) && suffixes.any(resource.filename::endsWith)
        }
    }
}

private class MigrationResource(
    private val classLoader: ClassLoader,
    private val relativePath: String,
) : LoadableResource() {
    private val absolutePath = "$migrationRoot/$relativePath"

    override fun getAbsolutePath(): String = absolutePath

    override fun getAbsolutePathOnDisk(): String = absolutePath

    override fun getFilename(): String = relativePath.substringAfterLast('/')

    override fun getRelativePath(): String = relativePath

    override fun read(): Reader = InputStreamReader(
        requireNotNull(classLoader.getResourceAsStream(absolutePath)) {
            "Missing Flyway migration resource: $absolutePath"
        },
        UTF_8,
    )

    fun exists(): Boolean = classLoader.getResource(absolutePath) != null
}

