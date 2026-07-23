package com.castab.resend.support

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.routing.bind
import org.http4k.routing.path
import org.http4k.routing.routes
import org.http4k.server.Http4kServer
import org.http4k.server.Jetty
import org.http4k.server.asServer
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Mirrors Resend's documented retrieve format, e.g. `2026-04-03 22:13:42.674981+00`. */
private val PROVIDER_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS'+00'")

private fun providerTimestamp(): String =
    OffsetDateTime.now(ZoneOffset.UTC).format(PROVIDER_TIMESTAMP)

/** In-process stand-in for the Resend API, mirroring the Express `FakeResendServer`. */
class FakeResendServer {
    private val jsonCodec = Json { ignoreUnknownKeys = true }
    private var sequence = 0
    private val idempotentResponses = mutableMapOf<String, String>()
    private val idempotentBatchResponses = mutableMapOf<String, Pair<String, List<String>>>()

    data class Send(val id: String, val idempotencyKey: String?, val input: JsonObject)

    val sends = mutableListOf<Send>()
    val batches = mutableListOf<Triple<String?, List<JsonObject>, List<String>>>()
    val received = mutableMapOf<String, JsonObject>()

    var failNextSendStatus: Int? = null
    var failNextBatchStatus: Int? = null
    var failNextBatchCode: String = "application_error"
    var malformedNextBatchResponse: Boolean = false

    /** While true, every `GET /emails/{id}` retrieval fails with a 500 (simulates provider outage). */
    var failSentRetrievals: Boolean = false

    private lateinit var server: Http4kServer
    val baseUrl: String get() = "http://localhost:${server.port()}"

    fun start() {
        reset()
        server = handler().asServer(Jetty(0)).start()
    }

    fun stop() {
        if (::server.isInitialized) server.stop()
    }

    fun reset() {
        sequence = 0
        sends.clear()
        batches.clear()
        idempotentResponses.clear()
        idempotentBatchResponses.clear()
        failNextSendStatus = null
        failNextBatchStatus = null
        failNextBatchCode = "application_error"
        malformedNextBatchResponse = false
        failSentRetrievals = false
        received.clear()
        received["em_received123"] = buildJsonObject {
            put("id", JsonPrimitive("em_received123"))
            put("message_id", JsonPrimitive("<received123@example.com>"))
            put("from", JsonPrimitive("external@example.com"))
            put("to", JsonArray(listOf(JsonPrimitive("inbox@example.com"))))
            put("subject", JsonPrimitive("Received Email"))
            put("created_at", JsonPrimitive("2026-07-19T03:52:03.099Z"))
            put("text", JsonPrimitive("Inbound test body"))
            put("html", JsonPrimitive("<p>Inbound test body</p>"))
            put("headers", buildJsonObject { put("from", JsonPrimitive("External Person <external@example.com>")) })
            put("reply_to", JsonArray(emptyList()))
        }
    }

    /** Registers a retrievable received-email fixture (call after `reset`). */
    fun addReceived(id: String, messageId: String, from: String = "external@example.com", references: String? = null) {
        received[id] = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("message_id", JsonPrimitive(messageId))
            put("from", JsonPrimitive(from))
            put("to", JsonArray(listOf(JsonPrimitive("inbox@example.com"))))
            put("subject", JsonPrimitive("Received Email"))
            put("created_at", JsonPrimitive("2026-07-19T03:52:03.099Z"))
            put("text", JsonPrimitive("Inbound test body"))
            put("html", JsonPrimitive("<p>Inbound test body</p>"))
            put("headers", buildJsonObject {
                put("from", JsonPrimitive(from))
                if (references != null) put("references", JsonPrimitive(references))
            })
            put("reply_to", JsonArray(emptyList()))
        }
    }

    private fun json(status: Int, body: JsonObject): Response =
        Response(Status(status, null)).header("content-type", "application/json").body(body.toString())

    private fun handler() = routes(
        "/emails/batch" bind Method.POST to { request ->
            val body = request.bodyString()
            val inputs = (jsonCodec.parseToJsonElement(body) as JsonArray).map { it as JsonObject }
            failNextBatchStatus?.let { status ->
                val code = failNextBatchCode
                failNextBatchStatus = null
                failNextBatchCode = "application_error"
                return@to json(status, buildJsonObject {
                    put("name", JsonPrimitive(code))
                    put("message", JsonPrimitive("simulated_batch_failure"))
                })
            }
            val idempotencyKey = request.header("idempotency-key")
            val existing = idempotencyKey?.let { idempotentBatchResponses[it] }
            if (existing != null && existing.first != body) {
                return@to json(409, buildJsonObject {
                    put("name", JsonPrimitive("invalid_idempotent_request"))
                    put("message", JsonPrimitive("batch payload changed"))
                })
            }
            val ids = existing?.second ?: inputs.map { "batch-${++sequence}" }
            if (existing == null) {
                batches.add(Triple(idempotencyKey, inputs, ids))
                inputs.forEachIndexed { index, input -> sends.add(Send(ids[index], idempotencyKey, input)) }
                if (idempotencyKey != null) idempotentBatchResponses[idempotencyKey] = body to ids
            }
            if (malformedNextBatchResponse) {
                malformedNextBatchResponse = false
                return@to json(200, buildJsonObject { put("data", buildJsonArray { ids.forEach { add(buildJsonObject {}) } }) })
            }
            json(200, buildJsonObject { put("data", buildJsonArray { ids.forEach { add(buildJsonObject { put("id", JsonPrimitive(it)) }) } }) })
        },
        "/emails" bind Method.POST to { request ->
            val body = request.bodyString()
            val input = jsonCodec.parseToJsonElement(body) as JsonObject
            failNextSendStatus?.let { status ->
                failNextSendStatus = null
                return@to json(status, buildJsonObject { put("error", JsonPrimitive("simulated_send_failure")) })
            }
            val idempotencyKey = request.header("idempotency-key")
            val existingId = idempotencyKey?.let { idempotentResponses[it] }
            val id = existingId ?: "sent-${++sequence}"
            if (existingId == null) {
                sends.add(Send(id, idempotencyKey, input))
                if (idempotencyKey != null) idempotentResponses[idempotencyKey] = id
            }
            json(200, buildJsonObject { put("id", JsonPrimitive(id)) })
        },
        "/emails/receiving/{id}" bind Method.GET to { request ->
            val id = request.path("id").orEmpty()
            received[id]?.let { json(200, it) } ?: json(404, buildJsonObject { put("error", JsonPrimitive("not_found")) })
        },
        "/emails/{id}" bind Method.GET to { request ->
            if (failSentRetrievals) {
                return@to json(500, buildJsonObject { put("error", JsonPrimitive("simulated_retrieval_failure")) })
            }
            val id = request.path("id").orEmpty()
            val sent = sends.firstOrNull { it.id == id }
            if (sent == null) {
                json(404, buildJsonObject { put("error", JsonPrimitive("not_found")) })
            } else {
                json(200, buildJsonObject {
                    put("id", JsonPrimitive(id))
                    put("message_id", JsonPrimitive("<$id@resend.test>"))
                    put("from", sent.input["from"] ?: JsonPrimitive(""))
                    put("to", sent.input["to"] ?: JsonArray(emptyList()))
                    put("subject", sent.input["subject"] ?: JsonPrimitive(""))
                    // Resend's retrieve response uses a Postgres-style timestamp, not an ISO instant.
                    put("created_at", JsonPrimitive(providerTimestamp()))
                    put("text", sent.input["text"] ?: kotlinx.serialization.json.JsonNull)
                    put("html", sent.input["html"] ?: kotlinx.serialization.json.JsonNull)
                })
            }
        },
    )
}
