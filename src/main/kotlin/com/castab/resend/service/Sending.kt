package com.castab.resend.service

import com.castab.resend.data.advisoryXactLock
import com.castab.resend.data.findDeliveryEvents
import com.castab.resend.data.findMessageById
import com.castab.resend.data.setMessageInternetMessageId
import com.castab.resend.data.tx
import com.castab.resend.data.updateMessageAccepted
import com.castab.resend.data.updateMessageStateDetail
import com.castab.resend.data.updateOutboundDeliveryState
import com.castab.resend.domain.EmailDirection
import com.castab.resend.domain.EmailMessage
import com.castab.resend.domain.EmailMessageState
import com.castab.resend.email.DELIVERY_EVENT_TYPES
import com.castab.resend.email.ResendApiError
import com.castab.resend.email.SendEmailInput
import com.castab.resend.email.reduceDeliveryEvents
import org.jdbi.v3.core.Handle
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

private val log = LoggerFactory.getLogger("com.castab.resend.service.Sending")

private val DISPLAY_NAME_PHRASE_PATTERN =
    Regex("^[A-Za-z0-9!#\$%&'*+\\-/=?^_`{|}~]+(?: [A-Za-z0-9!#\$%&'*+\\-/=?^_`{|}~]+)*$")

private const val PROVIDER_IDEMPOTENCY_SAFETY_MS = 23L * 60 * 60 * 1000

private fun formatAddress(address: String, name: String?): String =
    if (name != null) "$name <$address>" else address

fun formatReplyToAddress(address: String, name: String?): String {
    if (name == null) return address
    if (DISPLAY_NAME_PHRASE_PATTERN.matches(name)) return "$name <$address>"
    val escaped = name.replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$escaped\" <$address>"
}

fun buildSendEmailInput(message: EmailMessage): SendEmailInput = SendEmailInput(
    from = formatAddress(message.fromAddress, message.fromName),
    to = listOf(message.toAddress),
    replyTo = message.replyToAddress?.let { formatReplyToAddress(it, message.replyToName) },
    subject = message.subject,
    text = message.textBody,
    html = message.htmlBody,
    headers = message.inReplyToInternetMessageId?.let {
        mapOf(
            "In-Reply-To" to it,
            "References" to message.referenceInternetMessageIds.joinToString(" "),
        )
    },
)

/** Reduces stored delivery events for [resendEmailId] and applies the projection (holds an advisory lock). */
fun projectOutboundDeliveryState(handle: Handle, resendEmailId: String) {
    handle.advisoryXactLock(resendEmailId)
    val events = handle.findDeliveryEvents(resendEmailId, DELIVERY_EVENT_TYPES)
    val projection = reduceDeliveryEvents(events)
    val current = projection.current ?: return
    handle.updateOutboundDeliveryState(
        resendEmailId,
        current.state,
        current.detail,
        current.eventCreatedAt,
        projection.deliveredAt,
    )
}

fun Services.deliverPendingMessage(messageId: String): EmailMessage {
    var sendError: Throwable? = null
    val result = jdbi.tx { h ->
        h.advisoryXactLock(messageId)
        val message = h.findMessageById(messageId)
            ?: throw IllegalStateException("Message $messageId not found")
        if (message.state != EmailMessageState.PENDING) return@tx message
        try {
            val idempotencyKey = message.idempotencyKey
                ?: throw IllegalStateException("Pending message is missing an idempotency key")
            val sentId = configuredResend().send(buildSendEmailInput(message), "conversation/${message.id}")
            h.updateMessageAccepted(message.id, sentId)
            projectOutboundDeliveryState(h, sentId)
            h.findMessageById(message.id)!!
        } catch (error: Throwable) {
            sendError = error
            val knownFailure = error is ResendApiError ||
                (error.message?.startsWith("Missing RESEND_API_KEY") == true)
            val state = if (knownFailure) EmailMessageState.FAILED else EmailMessageState.INDETERMINATE
            h.updateMessageStateDetail(message.id, state, error.message?.take(1000) ?: "Unknown error")
            h.findMessageById(message.id)!!
        }
    }
    sendError?.let { throw it }
    return hydrateSentMetadata(result)
}

private fun Services.hydrateSentMetadata(message: EmailMessage): EmailMessage {
    if (message.state != EmailMessageState.ACCEPTED || message.resendEmailId == null || message.internetMessageId != null) {
        return message
    }
    var lastError: Throwable? = null
    for (delayMs in longArrayOf(0, 100, 250)) {
        if (delayMs > 0) Thread.sleep(delayMs)
        try {
            val retrieved = configuredResend().getSent(message.resendEmailId)
            return recordOutboundInternetMessageId(
                message.id,
                retrieved.messageId,
                parseInstant(retrieved.createdAt),
            )
        } catch (error: Throwable) {
            lastError = error
        }
    }
    log.warn("Sent email metadata is not available yet: {}", lastError?.message ?: "Unknown error")
    return message
}

fun Services.recoverPendingMessage(messageId: String): EmailMessage {
    val message = jdbi.withHandle<EmailMessage, RuntimeException> { it.findMessageById(messageId)!! }
    if (message.state != EmailMessageState.PENDING) return message

    val ageMs = System.currentTimeMillis() - message.createdAt.toInstant().toEpochMilli()
    if (ageMs >= PROVIDER_IDEMPOTENCY_SAFETY_MS) {
        jdbi.tx { h ->
            val current = h.findMessageById(message.id)!!
            if (current.state == EmailMessageState.PENDING) {
                h.updateMessageStateDetail(
                    message.id,
                    EmailMessageState.INDETERMINATE,
                    "Pending send exceeded the provider idempotency window",
                )
            }
        }
        return jdbi.withHandle<EmailMessage, RuntimeException> { it.findMessageById(message.id)!! }
    }

    return try {
        deliverPendingMessage(message.id)
    } catch (error: Throwable) {
        jdbi.withHandle<EmailMessage, RuntimeException> { it.findMessageById(message.id)!! }
    }
}

fun Services.ensureInternetMessageId(messageId: String): String? {
    val message = jdbi.withHandle<EmailMessage, RuntimeException> { it.findMessageById(messageId)!! }
    if (message.internetMessageId != null || message.resendEmailId == null) return message.internetMessageId

    val resend = configuredResend()
    val retrieved = if (message.direction == EmailDirection.INBOUND) {
        resend.getReceived(message.resendEmailId)
    } else {
        resend.getSent(message.resendEmailId)
    }
    if (message.direction == EmailDirection.OUTBOUND) {
        recordOutboundInternetMessageId(message.id, retrieved.messageId, parseInstant(retrieved.createdAt))
    } else {
        jdbi.withHandle<Unit, RuntimeException> { it.setMessageInternetMessageId(message.id, retrieved.messageId) }
    }
    return retrieved.messageId
}

/**
 * Parses provider timestamps in either strict ISO instant form (`2026-07-19T03:52:03.099Z`) or
 * Resend's Postgres-style retrieve format (`2026-04-03 22:13:42.674981+00`).
 */
internal fun parseInstant(value: String): OffsetDateTime {
    try {
        return Instant.parse(value).atOffset(ZoneOffset.UTC)
    } catch (_: DateTimeParseException) {
        // Fall through to the normalized offset form.
    }
    var normalized = value.trim().replaceFirst(' ', 'T')
    if (Regex("[+-]\\d{2}$").containsMatchIn(normalized)) normalized += ":00"
    return OffsetDateTime.parse(normalized).withOffsetSameInstant(ZoneOffset.UTC)
}
