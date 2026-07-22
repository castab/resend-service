package com.castab.resend

import org.http4k.server.Jetty
import org.http4k.server.asServer

fun main(args: Array<String>) {
    val config = Config.load()
    val source = config.databaseUrl?.let(::dataSource)
    if (args.singleOrNull() == "migrate") {
        requireNotNull(source) { "DATABASE_URL is required" }.use(::migrate)
        return
    }
    val server = application(config, source)
        .asServer(Jetty(config.port))
        .start()
    Runtime.getRuntime().addShutdownHook(Thread { server.stop(); source?.close() })
    println("resend-service listening on ${config.host}:${config.port}")
}
