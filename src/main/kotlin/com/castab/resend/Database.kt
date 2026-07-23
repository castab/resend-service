package com.castab.resend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.Location
import org.flywaydb.core.api.ResourceProvider
import org.flywaydb.core.api.resource.LoadableResource
import org.flywaydb.core.internal.resource.classpath.ClassPathResource
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
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

fun migrate(source: DataSource) {
    val classLoader = Thread.currentThread().contextClassLoader ?: Config::class.java.classLoader
    Flyway.configure()
        .dataSource(source)
        .locations(migrationLocation)
        .resourceProvider(IndexBackedResourceProvider(loadMigrationResources(classLoader)))
        .load()
        .migrate()
}

private fun loadMigrationResources(classLoader: ClassLoader): List<ClassPathResource> {
    val index = requireNotNull(classLoader.getResourceAsStream(migrationIndexPath)) {
        "Missing Flyway migration index: $migrationIndexPath"
    }
    return index.bufferedReader(UTF_8).useLines { lines ->
        lines.map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { path -> ClassPathResource(migrationLocation, path, classLoader, UTF_8) }
            .toList()
    }.onEach { resource ->
        require(resource.exists()) { "Missing Flyway migration resource: ${resource.absolutePath}" }
    }
}

private class IndexBackedResourceProvider(private val resources: List<ClassPathResource>) : ResourceProvider {
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

