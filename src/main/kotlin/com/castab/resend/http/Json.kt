package com.castab.resend.http

import com.castab.resend.domain.EmailConversation
import com.castab.resend.domain.EmailDirection
import com.castab.resend.domain.EmailMessage
import com.castab.resend.domain.wire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.http4k.core.Response
import org.http4k.core.Status
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** JSON reader/writer for controlled application payloads. */
val json: Json = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = true
}

/** JS `Date.toISOString()` parity: always UTC with exactly three fractional digits and a trailing `Z`. */
private val isoFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")

fun iso(value: OffsetDateTime): String =
    value.atZoneSameInstant(ZoneOffset.UTC).format(isoFormatter)

fun isoOrNull(value: OffsetDateTime?): JsonElement =
    if (value == null) JsonNull else JsonPrimitive(iso(value))

private fun str(value: String?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

fun jsonResponse(status: Status, body: JsonElement): Response =
    Response(status)
        .header("Content-Type", "application/json")
        .body(json.encodeToString(JsonElement.serializer(), body))

fun errorBody(message: String): JsonObject = buildJsonObject { put("error", JsonPrimitive(message)) }

fun error(status: Status, message: String): Response = jsonResponse(status, errorBody(message))

fun serializeMessage(message: EmailMessage): JsonObject = buildJsonObject {
    val outbound = message.direction == EmailDirection.OUTBOUND
    put("id", JsonPrimitive(message.id))
    put("parentMessageId", str(message.parentMessageId))
    put("direction", JsonPrimitive(message.direction.wire()))
    put("state", JsonPrimitive(message.state.wire()))
    put("stateDetail", str(message.stateDetail))
    put("deliveryState", if (outbound) JsonPrimitive(message.deliveryState?.wire() ?: "unknown") else JsonNull)
    put("deliveryStateDetail", if (outbound) str(message.deliveryStateDetail) else JsonNull)
    put("deliveredAt", if (outbound) isoOrNull(message.deliveredAt) else JsonNull)
    put("resendEmailId", str(message.resendEmailId))
    put("internetMessageId", str(message.internetMessageId))
    put("from", buildJsonObject {
        put("address", JsonPrimitive(message.fromAddress))
        put("name", str(message.fromName))
    })
    put("to", JsonPrimitive(message.toAddress))
    put("replyTo", str(message.replyToAddress))
    put("replyToName", str(message.replyToName))
    put("subject", JsonPrimitive(message.subject))
    put("text", str(message.textBody))
    put("html", str(message.htmlBody))
    put("createdAt", JsonPrimitive(iso(message.emailCreatedAt)))
}

fun serializeConversation(
    conversation: EmailConversation,
    replyToAddress: String,
    messages: List<EmailMessage>,
    hasMoreBefore: Boolean,
): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(conversation.id))
    put(
        "topic",
        if (conversation.topicType != null && conversation.externalTopicId != null) buildJsonObject {
            put("type", JsonPrimitive(conversation.topicType))
            put("externalId", JsonPrimitive(conversation.externalTopicId))
            put("title", JsonPrimitive(conversation.title))
        } else JsonNull,
    )
    put("title", JsonPrimitive(conversation.title))
    put("subject", JsonPrimitive(conversation.subject))
    put("participant", buildJsonObject {
        put("address", JsonPrimitive(conversation.participantAddress))
        put("name", str(conversation.participantName))
    })
    put("replyToAddress", JsonPrimitive(replyToAddress))
    put("lastMessageAt", JsonPrimitive(iso(conversation.lastMessageAt)))
    put("createdAt", JsonPrimitive(iso(conversation.createdAt)))
    put("updatedAt", JsonPrimitive(iso(conversation.updatedAt)))
    put("messages", buildJsonArray { messages.forEach { add(serializeMessage(it)) } })
    put("page", buildJsonObject {
        put("hasMoreBefore", JsonPrimitive(hasMoreBefore))
        put("before", if (hasMoreBefore) str(messages.firstOrNull()?.id) else JsonNull)
    })
}

/** Mirrors Express `sendResultResponse`: 502 for failed/indeterminate, 202 for pending, else 200. */
fun sendResultResponse(message: EmailMessage, conversationId: String): Response {
    val failed = message.state == com.castab.resend.domain.EmailMessageState.FAILED ||
        message.state == com.castab.resend.domain.EmailMessageState.INDETERMINATE
    val status = when {
        failed -> Status.BAD_GATEWAY
        message.state == com.castab.resend.domain.EmailMessageState.PENDING -> Status.ACCEPTED
        else -> Status.OK
    }
    val body = buildJsonObject {
        if (failed) put("error", JsonPrimitive("Email was not confirmed as sent"))
        put("conversationId", JsonPrimitive(conversationId))
        put("message", serializeMessage(message))
    }
    return jsonResponse(status, body)
}
