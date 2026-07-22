package com.castab.resend

import com.castab.resend.data.buildJdbi
import com.castab.resend.data.h
import com.castab.resend.email.isValidReplyToBaseAddress
import com.castab.resend.http.BodyErr
import com.castab.resend.http.BodyOk
import com.castab.resend.http.error
import com.castab.resend.http.jsonResponse
import com.castab.resend.http.pageLimit
import com.castab.resend.http.readJsonBody
import com.castab.resend.http.requireIdempotencyKey
import com.castab.resend.service.Services
import com.castab.resend.service.assignConversation
import com.castab.resend.service.createConversation
import com.castab.resend.service.drainEmailOutbox
import com.castab.resend.service.enqueueConversation
import com.castab.resend.service.enqueueMessage
import com.castab.resend.service.getConversationById
import com.castab.resend.service.getConversationByTopic
import com.castab.resend.service.handleWebhook
import com.castab.resend.service.listUnassigned
import com.castab.resend.service.sendMessage
import com.castab.resend.validation.Invalid
import com.castab.resend.validation.Valid
import com.castab.resend.validation.validateCreateBody
import com.castab.resend.validation.validateMessageBody
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.http4k.core.HttpHandler
import org.http4k.core.Method.GET
import org.http4k.core.Method.PATCH
import org.http4k.core.Method.POST
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.filter.ServerFilters
import org.http4k.lens.Path
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.jdbi.v3.core.Jdbi
import javax.sql.DataSource

fun application(config: Config, source: DataSource? = null): HttpHandler {
    val jdbi: Jdbi? = source?.let(::buildJdbi)
    val services: Services? = jdbi?.let { Services(config, it) }

    fun protected(expected: String?, next: HttpHandler): HttpHandler = { request ->
        when {
            expected.isNullOrBlank() -> error(Status.INTERNAL_SERVER_ERROR, "Server misconfiguration")
            !authorized(request.header("Authorization"), expected) ->
                error(Status.UNAUTHORIZED, "Unauthorized").header("WWW-Authenticate", "Bearer")
            else -> next(request)
        }
    }
    val conversation = { next: HttpHandler -> protected(config.conversationApiKey, next) }
    val drain = { next: HttpHandler -> protected(config.outboxDrainApiKey, next) }

    val conversationId = Path.of("conversationId")
    val topicType = Path.of("topicType")
    val externalTopicId = Path.of("externalTopicId")

    // Guards a route that needs the database; in a configured runtime `services` is always present.
    fun withServices(handler: Services.(Request) -> Response): HttpHandler = { request ->
        services?.handler(request) ?: error(Status.SERVICE_UNAVAILABLE, "Database is not configured")
    }

    val routes = routes(
        "/api/health/v1" bind GET to { request -> health(config, jdbi, request) },
        "/api/webhooks/resend/v1" bind POST to withServices { handleWebhook(it) },
        "/api/conversations/v1/outbox/drain" bind POST to drain(withServices { drainOutbox(it) }),
        "/api/conversations/v1/outbox" bind POST to conversation(withServices { createOrEnqueue(it, enqueue = true) }),
        "/api/conversations/v1/topics/$topicType/$externalTopicId" bind GET to conversation(
            withServices { getConversationByTopic(it, topicType(it), externalTopicId(it)) },
        ),
        "/api/conversations/v1" bind routes(
            GET to conversation(withServices { listUnassigned(it) }),
            POST to conversation(withServices { createOrEnqueue(it, enqueue = false) }),
        ),
        "/api/conversations/v1/$conversationId" bind routes(
            GET to conversation(withServices { getConversationById(it, conversationId(it)) }),
            PATCH to conversation(withServices { assignConversationRoute(it, conversationId(it)) }),
        ),
        "/api/conversations/v1/$conversationId/messages" bind POST to conversation(
            withServices { replyRoute(it, conversationId(it), enqueue = false) },
        ),
        "/api/conversations/v1/$conversationId/messages/outbox" bind POST to conversation(
            withServices { replyRoute(it, conversationId(it), enqueue = true) },
        ),
        "/openapi.json" bind GET to { resource("public/openapi.json", "application/json") },
        "/docs" bind GET to { resource("public/swagger.html", "text/html; charset=utf-8") },
    )
    return ServerFilters.CatchAll().then(ServerFilters.RequestTracing().then(routes))
}

// --- route bodies that combine idempotency / body parsing with the service call ---

private fun Services.createOrEnqueue(request: Request, enqueue: Boolean): Response {
    val (key, keyError) = requireIdempotencyKey(request)
    if (keyError != null) return keyError
    val body = when (val b = readJsonBody(request)) {
        is BodyErr -> return b.response
        is BodyOk -> b.value
    }
    return when (val validation = validateCreateBody(body)) {
        is Invalid -> error(Status.BAD_REQUEST, validation.error)
        is Valid -> if (enqueue) enqueueConversation(key!!, validation.value) else createConversation(key!!, validation.value)
    }
}

private fun Services.replyRoute(request: Request, conversationId: String, enqueue: Boolean): Response {
    val (key, keyError) = requireIdempotencyKey(request)
    if (keyError != null) return keyError
    val body = when (val b = readJsonBody(request)) {
        is BodyErr -> return b.response
        is BodyOk -> b.value
    }
    val validation = when (val v = validateMessageBody(body)) {
        is Invalid -> return error(Status.BAD_REQUEST, v.error)
        is Valid -> v.value
    }
    if (!com.castab.resend.validation.isUuid(conversationId)) {
        return error(Status.BAD_REQUEST, "Invalid conversation ID")
    }
    return if (enqueue) enqueueMessage(conversationId, key!!, validation) else sendMessage(conversationId, key!!, validation)
}

private fun Services.assignConversationRoute(request: Request, conversationId: String): Response {
    val body = when (val b = readJsonBody(request)) {
        is BodyErr -> return b.response
        is BodyOk -> b.value
    }
    return assignConversation(request, conversationId, body)
}

private fun Services.drainOutbox(request: Request): Response {
    val body = when (val b = readJsonBody(request)) {
        is BodyErr -> return b.response
        is BodyOk -> b.value
    }
    if (body !is JsonObject) return error(Status.BAD_REQUEST, "Request body must be an object")
    val limitElement = body["limit"]
    val limit = if (limitElement == null || limitElement is JsonNull) {
        100
    } else {
        val parsed = (limitElement as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
        if (parsed == null || parsed < 1 || parsed > 100) {
            return error(Status.BAD_REQUEST, "limit must be an integer between 1 and 100")
        }
        parsed
    }
    return try {
        jsonResponse(Status.OK, drainEmailOutbox(limit))
    } catch (ex: Throwable) {
        error(Status.INTERNAL_SERVER_ERROR, "Failed to drain email outbox")
    }
}

// --- health ---

private fun health(config: Config, jdbi: Jdbi?, request: Request): Response {
    if (!request.uri.query.isNullOrEmpty()) {
        return error(Status.BAD_REQUEST, "Health check does not accept query parameters")
    }
    val replyToValid = config.resendReplyTo?.let { isValidReplyToBaseAddress(it) } == true
    val configured = !config.databaseUrl.isNullOrBlank() &&
        !config.resendApiKey.isNullOrBlank() &&
        !config.webhookSecret.isNullOrBlank() &&
        !config.resendFrom.isNullOrBlank() &&
        !config.resendReplyTo.isNullOrBlank() &&
        replyToValid &&
        !config.conversationApiKey.isNullOrBlank() &&
        !config.outboxDrainApiKey.isNullOrBlank()
    if (!configured || jdbi == null) return unhealthy()
    return try {
        jdbi.h { it.createQuery("SELECT 1").mapTo(Int::class.java).one() }
        jsonResponse(Status.OK, buildJsonObject { put("status", JsonPrimitive("ok")) })
    } catch (e: Exception) {
        unhealthy()
    }
}

private fun unhealthy(): Response =
    jsonResponse(Status.SERVICE_UNAVAILABLE, buildJsonObject { put("status", JsonPrimitive("unhealthy")) })

private fun resource(name: String, type: String): Response {
    val bytes = Thread.currentThread().contextClassLoader.getResourceAsStream(name)?.use { it.readAllBytes() }
        ?: return Response(Status.NOT_FOUND)
    return Response(Status.OK).header("Content-Type", type).body(bytes.inputStream())
}
