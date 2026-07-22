package com.castab.resend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import java.net.URI
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

fun migrate(source: DataSource) { Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate() }

