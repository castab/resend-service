package com.castab.resend.service

import com.castab.resend.http.json
import com.castab.resend.validation.CreateConversationInput
import com.castab.resend.validation.MessageBodyInput
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.security.MessageDigest

/**
 * Canonical request hash, mirroring the Express `hashSendRequest` = `sha256(JSON.stringify(value))`.
 * Key order and omitted keys match the normalized validated objects so the hash stays stable and
 * self-consistent across requests.
 */
fun hashSendRequest(value: JsonElement): String {
    val compact = json.encodeToString(JsonElement.serializer(), value)
    val digest = MessageDigest.getInstance("SHA-256").digest(compact.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

private fun str(value: String?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

fun createCanonical(input: CreateConversationInput): JsonObject = buildJsonObject {
    put("topic", buildJsonObject {
        put("type", JsonPrimitive(input.topic.type))
        put("externalId", JsonPrimitive(input.topic.externalId))
        put("title", JsonPrimitive(input.topic.title))
    })
    put("participant", buildJsonObject {
        put("email", JsonPrimitive(input.participant.email))
        put("name", str(input.participant.name))
    })
    if (input.subject != null) put("subject", JsonPrimitive(input.subject))
    put("message", buildJsonObject {
        if (input.message.text != null) put("text", JsonPrimitive(input.message.text))
        if (input.message.html != null) put("html", JsonPrimitive(input.message.html))
        if (input.message.replyToName != null) put("replyToName", JsonPrimitive(input.message.replyToName))
    })
}

fun messageBodyCanonical(input: MessageBodyInput): JsonObject = buildJsonObject {
    if (input.text != null) put("text", JsonPrimitive(input.text))
    if (input.html != null) put("html", JsonPrimitive(input.html))
    if (input.replyToMessageId != null) put("replyToMessageId", JsonPrimitive(input.replyToMessageId))
    if (input.replyToName != null) put("replyToName", JsonPrimitive(input.replyToName))
}

fun hashCreate(input: CreateConversationInput): String = hashSendRequest(createCanonical(input))

fun hashOutboxOpen(input: CreateConversationInput): String = hashSendRequest(
    buildJsonObject {
        put("operation", JsonPrimitive("outbox-opening-v1"))
        put("request", createCanonical(input))
    },
)

fun hashReply(conversationId: String, input: MessageBodyInput): String = hashSendRequest(
    buildJsonObject {
        put("conversationId", JsonPrimitive(conversationId))
        messageBodyCanonical(input).forEach { (k, v) -> put(k, v) }
    },
)

fun hashOutboxReply(conversationId: String, input: MessageBodyInput): String = hashSendRequest(
    buildJsonObject {
        put("operation", JsonPrimitive("outbox-reply-v1"))
        put("conversationId", JsonPrimitive(conversationId))
        put("request", messageBodyCanonical(input))
    },
)
