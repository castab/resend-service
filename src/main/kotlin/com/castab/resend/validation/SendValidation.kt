package com.castab.resend.validation

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

const val MAX_TITLE_LENGTH = 255
const val MAX_SUBJECT_LENGTH = 255
const val MAX_NAME_LENGTH = 256
const val MAX_BODY_LENGTH = 1_048_576

private val TOPIC_TYPE_PATTERN = Regex("^[a-z][a-z0-9_-]{0,63}$")
private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
private val UUID_PATTERN =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)

sealed interface Validated<out T>
data class Valid<T>(val value: T) : Validated<T>
data class Invalid(val error: String) : Validated<Nothing>

data class TopicInput(val type: String, val externalId: String, val title: String)
data class ParticipantInput(val email: String, val name: String?)
data class MessageContent(val text: String?, val html: String?, val replyToName: String?)
data class CreateConversationInput(
    val topic: TopicInput,
    val participant: ParticipantInput,
    val subject: String?,
    val message: MessageContent,
)
data class MessageBodyInput(
    val text: String?,
    val html: String?,
    val replyToMessageId: String?,
    val replyToName: String?,
)

fun isUuid(value: String): Boolean = UUID_PATTERN.matches(value)

/** True when the element is a JSON object (matches TS `isRecord`). */
fun isRecord(value: JsonElement?): Boolean = value is JsonObject

/** Returns the string content when the element is a JSON string, else null (matches `typeof x === 'string'`). */
private fun JsonElement?.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun containsControlCharacter(value: String): Boolean =
    value.any { it.code < 0x20 || it.code == 0x7f }

/** Returns the string when it is header-safe (string, length <= max, no control chars), else null. */
private fun headerSafe(value: JsonElement?, maxLength: Int): String? {
    val s = value.stringOrNull() ?: return null
    return if (s.length <= maxLength && !containsControlCharacter(s)) s else null
}

private fun isEmailAddress(value: JsonElement?): Boolean {
    val s = value.stringOrNull() ?: return false
    return s.length <= 320 && EMAIL_PATTERN.matches(s)
}

private fun truthy(value: String?): Boolean = !value.isNullOrEmpty()

private sealed interface ReplyToNameResult
private data class ReplyToNameOk(val value: String?) : ReplyToNameResult
private object ReplyToNameInvalid : ReplyToNameResult

private fun normalizeReplyToName(value: JsonElement?): ReplyToNameResult {
    if (value == null || value is JsonNull) return ReplyToNameOk(null)
    val safe = headerSafe(value, MAX_NAME_LENGTH) ?: return ReplyToNameInvalid
    val normalized = safe.trim()
    if (normalized.isEmpty()) return ReplyToNameOk(null)
    return if (normalized.contains('<') || normalized.contains('>')) ReplyToNameInvalid else ReplyToNameOk(normalized)
}

private fun validTopic(topic: JsonObject): TopicInput? {
    val type = topic["type"].stringOrNull() ?: return null
    if (!TOPIC_TYPE_PATTERN.matches(type)) return null
    val externalId = topic["externalId"].stringOrNull() ?: return null
    if (externalId.isEmpty() || externalId.length > 255) return null
    val title = headerSafe(topic["title"], MAX_TITLE_LENGTH) ?: return null
    if (title.trim().isEmpty()) return null
    return TopicInput(type, externalId, title.trim())
}

fun validateCreateBody(value: JsonElement?): Validated<CreateConversationInput> {
    if (value !is JsonObject) return Invalid("topic and participant objects are required")
    val topicEl = value["topic"]
    val participantEl = value["participant"]
    if (topicEl !is JsonObject || participantEl !is JsonObject) {
        return Invalid("topic and participant objects are required")
    }
    val message = (value["message"] as? JsonObject) ?: JsonObject(emptyMap())

    val topic = validTopic(topicEl) ?: return Invalid("topic type, externalId, and title are invalid")

    if (!isEmailAddress(participantEl["email"])) {
        return Invalid("participant.email must be a valid email address")
    }
    val nameEl = participantEl["name"]
    if (nameEl != null && nameEl !is JsonNull && headerSafe(nameEl, MAX_NAME_LENGTH) == null) {
        return Invalid("participant.name is invalid")
    }

    val text = message["text"].stringOrNull()
    val html = message["html"].stringOrNull()
    val replyTo = normalizeReplyToName(message["replyToName"])
    if (replyTo is ReplyToNameInvalid) {
        return Invalid("message.replyToName must be a header-safe string of at most 256 characters")
    }
    if (!truthy(text) && !truthy(html)) return Invalid("message.text or message.html is required")
    if ((text?.length ?: 0) > MAX_BODY_LENGTH || (html?.length ?: 0) > MAX_BODY_LENGTH) {
        return Invalid("message.text and message.html are limited to 1 MiB each")
    }
    if (value.containsKey("subject") && headerSafe(value["subject"], MAX_SUBJECT_LENGTH) == null) {
        return Invalid("subject must be a header-safe string of at most 255 characters")
    }

    val participantName = participantEl["name"].stringOrNull()?.takeIf { it.trim().isNotEmpty() }?.trim()
    val subject = value["subject"].stringOrNull()?.takeIf { it.trim().isNotEmpty() }?.trim()
    return Valid(
        CreateConversationInput(
            topic = topic,
            participant = ParticipantInput(participantEl["email"].stringOrNull()!!, participantName),
            subject = subject,
            message = MessageContent(
                text = text?.takeIf { truthy(it) },
                html = html?.takeIf { truthy(it) },
                replyToName = (replyTo as ReplyToNameOk).value,
            ),
        ),
    )
}

fun validateMessageBody(value: JsonElement?): Validated<MessageBodyInput> {
    if (value !is JsonObject) return Invalid("Request body must be an object")
    val text = value["text"].stringOrNull()
    val html = value["html"].stringOrNull()
    val replyTo = normalizeReplyToName(value["replyToName"])
    if (replyTo is ReplyToNameInvalid) {
        return Invalid("replyToName must be a header-safe string of at most 256 characters")
    }
    if (!truthy(text) && !truthy(html)) return Invalid("text or html is required")
    if ((text?.length ?: 0) > MAX_BODY_LENGTH || (html?.length ?: 0) > MAX_BODY_LENGTH) {
        return Invalid("text and html are limited to 1 MiB each")
    }
    val replyToMessageId = value["replyToMessageId"]
    if (replyToMessageId != null && replyToMessageId !is JsonNull) {
        val id = replyToMessageId.stringOrNull()
        if (id == null || !isUuid(id)) return Invalid("replyToMessageId must be a UUID")
    }
    return Valid(
        MessageBodyInput(
            text = text?.takeIf { truthy(it) },
            html = html?.takeIf { truthy(it) },
            replyToMessageId = value["replyToMessageId"].stringOrNull(),
            replyToName = (replyTo as ReplyToNameOk).value,
        ),
    )
}

/** PATCH assignment topic validator: `{ topic: { type, externalId, title } }`. */
fun validateTopic(value: JsonElement?): Validated<TopicInput> {
    if (value !is JsonObject) return Invalid("topic is required")
    val topicEl = value["topic"]
    if (topicEl !is JsonObject) return Invalid("topic is required")
    return validTopic(topicEl)?.let { Valid(it) }
        ?: Invalid("topic type, externalId, and title are invalid")
}
