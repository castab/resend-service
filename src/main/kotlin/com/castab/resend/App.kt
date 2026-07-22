package com.castab.resend

import org.http4k.core.*
import org.http4k.core.Method.*
import org.http4k.filter.ServerFilters
import org.http4k.lens.Path
import org.http4k.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.sql.DataSource

private val json = Json {
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
private data class ErrorResponse(val error: String)

@Serializable
private data class HealthResponse(val status: String)

private fun error(status: Status, message: String) = Response(status)
    .header("Content-Type", "application/json")
    .body(json.encodeToString(ErrorResponse(message)))

fun application(config: Config, source: DataSource? = null): HttpHandler {
    fun protected(expected: String?, next: HttpHandler): HttpHandler = { request ->
        when {
            expected.isNullOrBlank() -> error(Status.INTERNAL_SERVER_ERROR, "Server misconfiguration")
            !authorized(request.header("Authorization"), expected) -> error(Status.UNAUTHORIZED, "Unauthorized").header("WWW-Authenticate", "Bearer")
            else -> next(request)
        }
    }
    val unavailable: HttpHandler = { error(Status.NOT_IMPLEMENTED, "Operation is pending in the Kotlin data-layer port") }
    val conversation = { next: HttpHandler -> protected(config.conversationApiKey, next) }
    val drain = { next: HttpHandler -> protected(config.outboxDrainApiKey, next) }
    val conversationId = Path.of("conversationId")
    val topicType = Path.of("topicType")
    val externalTopicId = Path.of("externalTopicId")

    val routes = routes(
        "/api/health/v1" bind GET to {
            val databaseUp = runCatching { source?.connection?.use { it.isValid(2) } ?: false }.getOrDefault(false)
            val healthy = config.configured && databaseUp
            Response(if (healthy) Status.OK else Status.SERVICE_UNAVAILABLE)
                .header("Content-Type", "application/json")
                .body(json.encodeToString(HealthResponse(if (healthy) "ok" else "unavailable")))
        },
        "/api/webhooks/resend/v1" bind POST to { error(Status.NOT_IMPLEMENTED, "Webhook projection is pending in the Kotlin port") },
        "/api/conversations/v1/outbox/drain" bind POST to drain(unavailable),
        "/api/conversations/v1/outbox" bind POST to conversation(unavailable),
        "/api/conversations/v1/topics/$topicType/$externalTopicId" bind GET to conversation(unavailable),
        "/api/conversations/v1" bind routes(GET to conversation(unavailable), POST to conversation(unavailable)),
        "/api/conversations/v1/$conversationId" bind routes(GET to conversation(unavailable), PATCH to conversation(unavailable)),
        "/api/conversations/v1/$conversationId/messages" bind POST to conversation(unavailable),
        "/api/conversations/v1/$conversationId/messages/outbox" bind POST to conversation(unavailable),
        "/openapi.json" bind GET to { resource("public/openapi.json", "application/json") },
        "/docs" bind GET to { resource("public/swagger.html", "text/html; charset=utf-8") },
    )
    return ServerFilters.CatchAll().then(ServerFilters.RequestTracing().then(routes))
}

private fun resource(name: String, type: String): Response {
    val bytes = Thread.currentThread().contextClassLoader.getResourceAsStream(name)?.use { it.readAllBytes() }
        ?: return Response(Status.NOT_FOUND)
    return Response(Status.OK).header("Content-Type", type).body(bytes.inputStream())
}
