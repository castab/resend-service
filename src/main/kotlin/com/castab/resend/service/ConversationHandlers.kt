package com.castab.resend.service

import com.castab.resend.data.NewConversation
import com.castab.resend.data.NewMessage
import com.castab.resend.data.advisoryXactLock
import com.castab.resend.data.assignConversationTopic
import com.castab.resend.data.bumpConversationLastMessageAt
import com.castab.resend.data.findConversationById
import com.castab.resend.data.findConversationByTopic
import com.castab.resend.data.findConversationMessages
import com.castab.resend.data.findFirstMessageWithStateNot
import com.castab.resend.data.findLatestParentCandidate
import com.castab.resend.data.findMessageById
import com.castab.resend.data.findMessageByIdempotencyKey
import com.castab.resend.data.findMessageInConversation
import com.castab.resend.data.h
import com.castab.resend.data.insertConversation
import com.castab.resend.data.insertMessage
import com.castab.resend.data.insertOutboxEntry
import com.castab.resend.data.isUniqueViolation
import com.castab.resend.data.listUnassignedConversations
import com.castab.resend.data.tx
import com.castab.resend.data.updateConversationReopen
import com.castab.resend.domain.EmailConversation
import com.castab.resend.domain.EmailDeliveryState
import com.castab.resend.domain.EmailDirection
import com.castab.resend.domain.EmailMessage
import com.castab.resend.domain.EmailMessageState
import com.castab.resend.email.buildConversationReplyTo
import com.castab.resend.email.buildReferences
import com.castab.resend.email.createReplySubject
import com.castab.resend.email.createRoutingToken
import com.castab.resend.email.isValidReplyToBaseAddress
import com.castab.resend.email.normalizeSubject
import com.castab.resend.email.parseAddress
import com.castab.resend.http.error
import com.castab.resend.http.iso
import com.castab.resend.http.json
import com.castab.resend.http.jsonResponse
import com.castab.resend.http.pageLimit
import com.castab.resend.http.sendResultResponse
import com.castab.resend.http.serializeConversation
import com.castab.resend.http.serializeMessage
import com.castab.resend.validation.CreateConversationInput
import com.castab.resend.validation.Invalid
import com.castab.resend.validation.MessageBodyInput
import com.castab.resend.validation.Valid
import com.castab.resend.validation.isUuid
import com.castab.resend.validation.validateTopic
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.http4k.core.Response
import org.http4k.core.Status
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64

private val log = LoggerFactory.getLogger("com.castab.resend.service.ConversationHandlers")

private data class SendConfig(val from: String, val replyToBase: String)

private fun Services.sendConfig(): SendConfig? {
    val from = config.resendFrom
    val replyTo = config.resendReplyTo
    if (from.isNullOrBlank() || replyTo.isNullOrBlank() || !isValidReplyToBaseAddress(replyTo)) return null
    return SendConfig(from, replyTo)
}

private fun now(): OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)

private fun conflictDifferentRequest() =
    error(Status.CONFLICT, "Idempotency key was already used for a different request")

// ---------------------------------------------------------------------------
// Create conversation (synchronous send) and outbox enqueue
// ---------------------------------------------------------------------------

fun Services.createConversation(idempotencyKey: String, input: CreateConversationInput): Response {
    val sendConfig = sendConfig() ?: return error(Status.INTERNAL_SERVER_ERROR, "Server misconfiguration")
    val requestHash = hashCreate(input)

    jdbi.h { it.findMessageByIdempotencyKey(idempotencyKey) }?.let { existing ->
        if (existing.requestHash != requestHash) return conflictDifferentRequest()
        return sendResultResponse(recoverPendingMessage(existing.id), existing.conversationId)
    }

    val from = parseAddress(sendConfig.from)
    val subject = normalizeSubject(input.subject ?: input.topic.title)
    val moment = now()
    val routingToken = createRoutingToken()
    val replyToAddress = buildConversationReplyTo(sendConfig.replyToBase, routingToken)

    val created = try {
        jdbi.tx { h ->
            val conversation = h.insertConversation(
                NewConversation(
                    routingToken = routingToken,
                    topicType = input.topic.type,
                    externalTopicId = input.topic.externalId,
                    title = input.topic.title,
                    subject = subject,
                    participantAddress = input.participant.email,
                    participantName = input.participant.name,
                    lastMessageAt = moment,
                ),
            )
            val message = h.insertMessage(
                openingMessage(conversation.id, from, input, replyToAddress, subject, moment, idempotencyKey, requestHash),
            )
            conversation.id to message.id
        }
    } catch (ex: RuntimeException) {
        if (!isUniqueViolation(ex)) throw ex
        jdbi.h { it.findMessageByIdempotencyKey(idempotencyKey) }?.let { raced ->
            return if (raced.requestHash != requestHash) {
                conflictDifferentRequest()
            } else {
                sendResultResponse(recoverPendingMessage(raced.id), raced.conversationId)
            }
        }
        val reopened = try {
            reopenFailedTopicConversation(input, from, subject, idempotencyKey, requestHash, sendConfig, withOutbox = false)
        } catch (reopenError: RuntimeException) {
            if (!isUniqueViolation(reopenError)) throw reopenError
            val racedReopen = jdbi.h { it.findMessageByIdempotencyKey(idempotencyKey) }
            if (racedReopen != null && racedReopen.requestHash == requestHash) {
                return sendResultResponse(recoverPendingMessage(racedReopen.id), racedReopen.conversationId)
            }
            return error(Status.CONFLICT, "Idempotency key is already in use")
        }
        return if (reopened != null) {
            deliverOpeningMessage(reopened.first, reopened.second)
        } else {
            error(Status.CONFLICT, "A conversation already exists for this topic")
        }
    }
    return deliverOpeningMessage(created.first, created.second)
}

fun Services.enqueueConversation(idempotencyKey: String, input: CreateConversationInput): Response {
    val sendConfig = sendConfig() ?: return error(Status.INTERNAL_SERVER_ERROR, "Server misconfiguration")
    val requestHash = hashOutboxOpen(input)

    jdbi.h { it.findMessageByIdempotencyKey(idempotencyKey) }?.let { existing ->
        if (existing.requestHash != requestHash) return conflictDifferentRequest()
        return sendResultResponse(existing, existing.conversationId)
    }

    val from = parseAddress(sendConfig.from)
    val subject = normalizeSubject(input.subject ?: input.topic.title)
    val moment = now()
    val routingToken = createRoutingToken()
    val replyToAddress = buildConversationReplyTo(sendConfig.replyToBase, routingToken)

    val created = try {
        jdbi.tx { h ->
            val conversation = h.insertConversation(
                NewConversation(
                    routingToken = routingToken,
                    topicType = input.topic.type,
                    externalTopicId = input.topic.externalId,
                    title = input.topic.title,
                    subject = subject,
                    participantAddress = input.participant.email,
                    participantName = input.participant.name,
                    lastMessageAt = moment,
                ),
            )
            val message = h.insertMessage(
                openingMessage(conversation.id, from, input, replyToAddress, subject, moment, idempotencyKey, requestHash),
            )
            h.insertOutboxEntry(message.id)
            conversation.id to message.id
        }
    } catch (ex: RuntimeException) {
        if (!isUniqueViolation(ex)) throw ex
        return resolveEnqueueConflict(idempotencyKey, requestHash, input, from, subject, sendConfig)
    }
    val message = jdbi.h { it.findMessageById(created.second)!! }
    return enqueueAccepted(created.first, message)
}

private fun openingMessage(
    conversationId: String,
    from: com.castab.resend.email.Mailbox,
    input: CreateConversationInput,
    replyToAddress: String,
    subject: String,
    moment: OffsetDateTime,
    idempotencyKey: String,
    requestHash: String,
) = NewMessage(
    conversationId = conversationId,
    direction = EmailDirection.OUTBOUND,
    state = EmailMessageState.PENDING,
    deliveryState = EmailDeliveryState.UNKNOWN,
    fromAddress = from.address,
    fromName = from.name,
    toAddress = input.participant.email,
    replyToAddress = replyToAddress,
    replyToName = input.message.replyToName,
    subject = subject,
    textBody = input.message.text,
    htmlBody = input.message.html,
    emailCreatedAt = moment,
    idempotencyKey = idempotencyKey,
    requestHash = requestHash,
)

private fun Services.deliverOpeningMessage(conversationId: String, messageId: String): Response = try {
    val message = deliverPendingMessage(messageId)
    jsonResponse(Status.CREATED, buildJsonObject {
        put("conversationId", JsonPrimitive(conversationId))
        put("message", serializeMessage(message))
    })
} catch (error: Throwable) {
    log.error("Failed to send opening conversation email: {}", error.message ?: "Unknown error")
    val message = jdbi.h { it.findMessageById(messageId)!! }
    jsonResponse(Status.BAD_GATEWAY, buildJsonObject {
        put("error", JsonPrimitive("Failed to send email"))
        put("conversationId", JsonPrimitive(conversationId))
        put("message", serializeMessage(message))
    })
}

private fun Services.enqueueAccepted(conversationId: String, message: EmailMessage): Response =
    jsonResponse(Status.ACCEPTED, buildJsonObject {
        put("conversationId", JsonPrimitive(conversationId))
        put("message", serializeMessage(message))
    })

private fun Services.resolveEnqueueConflict(
    idempotencyKey: String,
    requestHash: String,
    input: CreateConversationInput,
    from: com.castab.resend.email.Mailbox,
    subject: String,
    sendConfig: SendConfig,
): Response {
    jdbi.h { it.findMessageByIdempotencyKey(idempotencyKey) }?.let { raced ->
        return if (raced.requestHash != requestHash) {
            conflictDifferentRequest()
        } else {
            sendResultResponse(raced, raced.conversationId)
        }
    }
    val reopened = try {
        reopenFailedTopicConversation(input, from, subject, idempotencyKey, requestHash, sendConfig, withOutbox = true)
    } catch (reopenError: RuntimeException) {
        if (isUniqueViolation(reopenError)) {
            val racedReopen = jdbi.h { it.findMessageByIdempotencyKey(idempotencyKey) }
            if (racedReopen != null && racedReopen.requestHash == requestHash) {
                return sendResultResponse(racedReopen, racedReopen.conversationId)
            }
            return error(Status.CONFLICT, "Idempotency key is already in use")
        }
        throw reopenError
    }
    if (reopened != null) {
        val message = jdbi.h { it.findMessageById(reopened.second)!! }
        return enqueueAccepted(reopened.first, message)
    }
    return error(Status.CONFLICT, "A conversation already exists for this topic")
}

private fun Services.reopenFailedTopicConversation(
    input: CreateConversationInput,
    from: com.castab.resend.email.Mailbox,
    subject: String,
    idempotencyKey: String,
    requestHash: String,
    sendConfig: SendConfig,
    withOutbox: Boolean,
): Pair<String, String>? {
    val conversation = jdbi.h { it.findConversationByTopic(input.topic.type, input.topic.externalId) } ?: return null
    val moment = now()
    return jdbi.tx { h ->
        h.advisoryXactLock(conversation.id)
        if (h.findFirstMessageWithStateNot(conversation.id, EmailMessageState.FAILED) != null) return@tx null
        h.updateConversationReopen(
            conversation.id,
            input.topic.title,
            subject,
            input.participant.email,
            input.participant.name,
            moment,
        )
        val message = h.insertMessage(
            NewMessage(
                conversationId = conversation.id,
                direction = EmailDirection.OUTBOUND,
                state = EmailMessageState.PENDING,
                deliveryState = EmailDeliveryState.UNKNOWN,
                fromAddress = from.address,
                fromName = from.name,
                toAddress = input.participant.email,
                replyToAddress = buildConversationReplyTo(sendConfig.replyToBase, conversation.routingToken),
                replyToName = input.message.replyToName,
                subject = subject,
                textBody = input.message.text,
                htmlBody = input.message.html,
                emailCreatedAt = moment,
                idempotencyKey = idempotencyKey,
                requestHash = requestHash,
            ),
        )
        if (withOutbox) h.insertOutboxEntry(message.id)
        conversation.id to message.id
    }
}

// ---------------------------------------------------------------------------
// Replies (synchronous and enqueue)
// ---------------------------------------------------------------------------

fun Services.sendMessage(conversationId: String, idempotencyKey: String, input: MessageBodyInput): Response =
    reply(conversationId, idempotencyKey, input, hashReply(conversationId, input), withOutbox = false)

fun Services.enqueueMessage(conversationId: String, idempotencyKey: String, input: MessageBodyInput): Response =
    reply(conversationId, idempotencyKey, input, hashOutboxReply(conversationId, input), withOutbox = true)

private fun Services.reply(
    conversationId: String,
    idempotencyKey: String,
    input: MessageBodyInput,
    requestHash: String,
    withOutbox: Boolean,
): Response {
    jdbi.h { it.findMessageByIdempotencyKey(idempotencyKey) }?.let { existing ->
        if (existing.requestHash != requestHash || existing.conversationId != conversationId) {
            return conflictDifferentRequest()
        }
        return if (withOutbox) sendResultResponse(existing, conversationId)
        else sendResultResponse(recoverPendingMessage(existing.id), conversationId)
    }

    val conversation = jdbi.h { it.findConversationById(conversationId) }
        ?: return error(Status.NOT_FOUND, "Conversation not found")

    // An explicit reply parent must belong to the requested conversation; only an omitted
    // parent falls back to the latest eligible message.
    val parent = jdbi.h { h ->
        val explicitId = input.replyToMessageId
        if (explicitId != null) {
            h.findMessageInConversation(explicitId, conversationId)
        } else {
            h.findLatestParentCandidate(
                conversationId,
                listOf(EmailMessageState.RECEIVED, EmailMessageState.ACCEPTED),
            )
        }
    } ?: return error(Status.NOT_FOUND, "Reply parent not found")

    val parentInternetMessageId = try {
        ensureInternetMessageId(parent.id)
    } catch (ex: Throwable) {
        log.error("Failed to retrieve reply parent metadata: {}", ex.message ?: "Unknown error")
        return error(Status.SERVICE_UNAVAILABLE, "Reply parent threading metadata is unavailable")
    } ?: return error(Status.CONFLICT, "Reply parent threading metadata is unavailable")

    val sendConfig = sendConfig() ?: return error(Status.INTERNAL_SERVER_ERROR, "Server misconfiguration")
    val from = parseAddress(sendConfig.from)
    val replyToAddress = buildConversationReplyTo(sendConfig.replyToBase, conversation.routingToken)
    val references = buildReferences(parent.referenceInternetMessageIds, parentInternetMessageId)
    val moment = now()

    val pending = try {
        jdbi.tx { h ->
            val message = h.insertMessage(
                NewMessage(
                    conversationId = conversationId,
                    parentMessageId = parent.id,
                    direction = EmailDirection.OUTBOUND,
                    state = EmailMessageState.PENDING,
                    deliveryState = EmailDeliveryState.UNKNOWN,
                    inReplyToInternetMessageId = parentInternetMessageId,
                    referenceInternetMessageIds = references,
                    fromAddress = from.address,
                    fromName = from.name,
                    toAddress = conversation.participantAddress,
                    replyToAddress = replyToAddress,
                    replyToName = input.replyToName,
                    subject = createReplySubject(conversation.subject),
                    textBody = input.text,
                    htmlBody = input.html,
                    emailCreatedAt = moment,
                    idempotencyKey = idempotencyKey,
                    requestHash = requestHash,
                ),
            )
            if (withOutbox) h.insertOutboxEntry(message.id)
            h.bumpConversationLastMessageAt(conversationId, moment)
            message
        }
    } catch (ex: RuntimeException) {
        if (!isUniqueViolation(ex)) throw ex
        val raced = jdbi.h { it.findMessageByIdempotencyKey(idempotencyKey) }
        if (raced != null && raced.requestHash == requestHash && raced.conversationId == conversationId) {
            return if (withOutbox) sendResultResponse(raced, conversationId)
            else sendResultResponse(recoverPendingMessage(raced.id), conversationId)
        }
        return error(Status.CONFLICT, "Idempotency key is already in use")
    }

    if (withOutbox) {
        return enqueueAccepted(conversationId, pending)
    }
    return try {
        val message = deliverPendingMessage(pending.id)
        jsonResponse(Status.CREATED, buildJsonObject {
            put("conversationId", JsonPrimitive(conversationId))
            put("message", serializeMessage(message))
        })
    } catch (error: Throwable) {
        log.error("Failed to send conversation reply: {}", error.message ?: "Unknown error")
        val message = jdbi.h { it.findMessageById(pending.id)!! }
        jsonResponse(Status.BAD_GATEWAY, buildJsonObject {
            put("error", JsonPrimitive("Failed to send email"))
            put("conversationId", JsonPrimitive(conversationId))
            put("message", serializeMessage(message))
        })
    }
}

// ---------------------------------------------------------------------------
// Reads / assignment
// ---------------------------------------------------------------------------

fun Services.getConversationById(request: org.http4k.core.Request, conversationId: String): Response {
    if (!isUuid(conversationId)) return error(Status.BAD_REQUEST, "Invalid conversation ID")
    return conversationResponse(request) { it.findConversationById(conversationId) }
}

fun Services.getConversationByTopic(
    request: org.http4k.core.Request,
    topicType: String,
    externalTopicId: String,
): Response {
    if (!Regex("^[a-z][a-z0-9_-]{0,63}$").matches(topicType) || externalTopicId.isEmpty()) {
        return error(Status.BAD_REQUEST, "Invalid topic identity")
    }
    return conversationResponse(request) { it.findConversationByTopic(topicType, externalTopicId) }
}

fun Services.assignConversation(request: org.http4k.core.Request, conversationId: String, body: kotlinx.serialization.json.JsonElement): Response {
    val topic = when (val v = validateTopic(body)) {
        is Invalid -> return error(Status.BAD_REQUEST, v.error)
        is Valid -> v.value
    }
    if (!isUuid(conversationId)) return error(Status.BAD_REQUEST, "Invalid conversation ID")
    val assigned = try {
        jdbi.h { it.assignConversationTopic(conversationId, topic.type, topic.externalId, topic.title) }
    } catch (ex: RuntimeException) {
        if (isUniqueViolation(ex)) return error(Status.CONFLICT, "A conversation already exists for this topic")
        throw ex
    }
    if (assigned == 0) {
        val existing = jdbi.h { it.findConversationById(conversationId) }
        return if (existing != null) {
            error(Status.CONFLICT, "Conversation is already assigned to a topic")
        } else {
            error(Status.NOT_FOUND, "Conversation not found")
        }
    }
    return conversationResponse(request) { it.findConversationById(conversationId) }
}

private fun Services.conversationResponse(
    request: org.http4k.core.Request,
    find: (org.jdbi.v3.core.Handle) -> EmailConversation?,
): Response {
    val conversation = jdbi.h(find) ?: return error(Status.NOT_FOUND, "Conversation not found")
    val limit = pageLimit(request)
    val beforeId = request.query("before")
    if (beforeId != null && !isUuid(beforeId)) return error(Status.BAD_REQUEST, "Invalid message cursor")
    val before = beforeId?.let { id -> jdbi.h { it.findMessageInConversation(id, conversation.id) } }
    if (beforeId != null && before == null) return error(Status.BAD_REQUEST, "Invalid message cursor")

    val messages = jdbi.h { it.findConversationMessages(conversation.id, before, limit + 1) }
    val hasMoreBefore = messages.size > limit
    val page = messages.take(limit).reversed()
    val replyToAddress = buildConversationReplyTo(config.resendReplyTo ?: "", conversation.routingToken)
    return jsonResponse(Status.OK, serializeConversation(conversation, replyToAddress, page, hasMoreBefore))
}

fun Services.listUnassigned(request: org.http4k.core.Request): Response {
    if (request.query("assignment") != "unassigned") {
        return error(Status.BAD_REQUEST, "Only assignment=unassigned is supported")
    }
    val limit = pageLimit(request)
    val beforeValue = request.query("before")
    val before = beforeValue?.let { decodeConversationCursor(it) }
    if (beforeValue != null && before == null) return error(Status.BAD_REQUEST, "Invalid conversation cursor")

    val conversations = jdbi.h { it.listUnassignedConversations(before, limit + 1) }
    val hasMore = conversations.size > limit
    val page = conversations.take(limit)
    val body = buildJsonObject {
        put("conversations", buildJsonArray {
            page.forEach { conversation ->
                add(buildJsonObject {
                    put("id", JsonPrimitive(conversation.id))
                    put("title", JsonPrimitive(conversation.title))
                    put("participant", buildJsonObject {
                        put("address", JsonPrimitive(conversation.participantAddress))
                        put("name", conversation.participantName?.let { JsonPrimitive(it) } ?: JsonNull)
                    })
                    put("lastMessageAt", JsonPrimitive(iso(conversation.lastMessageAt)))
                })
            }
        })
        put("page", buildJsonObject {
            put("hasMore", JsonPrimitive(hasMore))
            put("before", if (hasMore && page.isNotEmpty()) JsonPrimitive(encodeConversationCursor(page.last())) else JsonNull)
        })
    }
    return jsonResponse(Status.OK, body)
}

private fun encodeConversationCursor(conversation: EmailConversation): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(
        json.encodeToString(
            JsonArray.serializer(),
            JsonArray(listOf(JsonPrimitive(iso(conversation.lastMessageAt)), JsonPrimitive(conversation.id))),
        ).toByteArray(Charsets.UTF_8),
    )

private fun decodeConversationCursor(value: String): Pair<OffsetDateTime, String>? = try {
    val decoded = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
    val parsed = json.parseToJsonElement(decoded) as? JsonArray
    if (parsed == null || parsed.size != 2) {
        null
    } else {
        val ts = (parsed[0] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val id = (parsed[1] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (ts == null || id == null || !isUuid(id)) {
            null
        } else {
            runCatching { OffsetDateTime.parse(ts) }.getOrNull()?.let { it to id }
        }
    }
} catch (e: Exception) {
    null
}
