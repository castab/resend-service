package com.castab.resend.service

import com.castab.resend.data.ContactEventRow
import com.castab.resend.data.DomainEventRow
import com.castab.resend.data.EmailEventRow
import com.castab.resend.data.findEmailEventIdBySvixId
import com.castab.resend.data.findMessageByResendEmailId
import com.castab.resend.data.h
import com.castab.resend.data.insertContactEvent
import com.castab.resend.data.insertDomainEvent
import com.castab.resend.data.insertEmailEvent
import com.castab.resend.data.tx
import com.castab.resend.email.ContactEventData
import com.castab.resend.email.DomainEventData
import com.castab.resend.email.EmailEventData
import com.castab.resend.email.VerifyErr
import com.castab.resend.email.WebhookEnvelope
import com.castab.resend.email.WebhookVerifier
import com.castab.resend.email.deliveryDetail
import com.castab.resend.email.extractMessageIds
import com.castab.resend.email.getHeader
import com.castab.resend.email.isContactEvent
import com.castab.resend.email.isDomainEvent
import com.castab.resend.email.isEmailEvent
import com.castab.resend.email.isValidReplyToBaseAddress
import com.castab.resend.email.parseAddress
import com.castab.resend.http.RawBodyErr
import com.castab.resend.http.RawBodyOk
import com.castab.resend.http.error
import com.castab.resend.http.json
import com.castab.resend.http.jsonResponse
import com.castab.resend.http.readBoundedBody
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.castab.resend.service.WebhookService")

/** Compact JSON that omits nulls/defaults, for jsonb columns (tags, domain records). */
private val compactJson = Json { encodeDefaults = false; explicitNulls = false }

fun Services.handleWebhook(request: Request): Response {
    val secret = config.webhookSecret
    if (secret.isNullOrBlank()) return error(Status.INTERNAL_SERVER_ERROR, "Server misconfiguration")

    val svixId = request.header("svix-id")
    val svixTimestamp = request.header("svix-timestamp")
    val svixSignature = request.header("svix-signature")
    if (svixId.isNullOrEmpty() || svixTimestamp.isNullOrEmpty() || svixSignature.isNullOrEmpty()) {
        return error(Status.BAD_REQUEST, "Missing required Svix headers")
    }

    // Bound the unauthenticated payload before verification or JSON decoding.
    val rawBody = when (val raw = readBoundedBody(request)) {
        is RawBodyErr -> return raw.response
        is RawBodyOk -> String(raw.bytes, Charsets.UTF_8)
    }
    val verification = WebhookVerifier.verify(rawBody, svixId, svixTimestamp, svixSignature, secret)
    if (verification is VerifyErr) {
        log.error("Webhook verification failed: {}", verification.error)
        return error(Status.UNAUTHORIZED, "Invalid webhook signature")
    }

    return try {
        val envelope = json.decodeFromString(WebhookEnvelope.serializer(), rawBody)
        when {
            isEmailEvent(envelope.type) -> insertEmailEvent(envelope, svixId)
            isContactEvent(envelope.type) -> insertContactEvent(envelope, svixId)
            isDomainEvent(envelope.type) -> insertDomainEvent(envelope, svixId)
            else -> return error(Status.BAD_REQUEST, "Unknown event type")
        }
        jsonResponse(Status.OK, buildJsonObject { put("received", JsonPrimitive(true)) })
    } catch (ex: Throwable) {
        log.error("Database insertion failed: {}", ex.message ?: "Unknown error")
        error(Status.INTERNAL_SERVER_ERROR, "Failed to process webhook")
    }
}

private fun Services.insertEmailEvent(envelope: WebhookEnvelope, svixId: String) {
    val data = json.decodeFromJsonElement(EmailEventData.serializer(), envelope.data)
    val existingEmailId = jdbi.h { it.findEmailEventIdBySvixId(svixId) }
    if (existingEmailId != null && envelope.type != "email.received") {
        jdbi.tx { h -> projectOutboundDeliveryState(h, existingEmailId) }
        return
    }

    val row = emailEventRow(envelope, data, svixId)
    if (envelope.type != "email.received") {
        jdbi.tx { h ->
            h.insertEmailEvent(row)
            projectOutboundDeliveryState(h, data.emailId)
        }
        return
    }

    val configuredReplyTo = config.resendReplyTo
    if (configuredReplyTo.isNullOrBlank() || !isValidReplyToBaseAddress(configuredReplyTo)) {
        throw IllegalStateException("Missing or invalid RESEND_REPLY_TO configuration")
    }
    jdbi.h { it.insertEmailEvent(row) }

    val existingReceived = jdbi.h { it.findMessageByResendEmailId(data.emailId) }
    val resend = configuredResend()
    if (existingReceived != null) {
        // The projection already exists, so a replayed delivery must acknowledge idempotently:
        // a transient hydration failure is logged rather than surfaced as a 500.
        val ancestry = existingReceived.referenceInternetMessageIds +
            (existingReceived.inReplyToInternetMessageId?.let { listOf(it) } ?: emptyList())
        try {
            hydrateReferencedOutboundMessages(
                resend,
                existingReceived.fromAddress,
                ancestry,
                existingReceived.inReplyToInternetMessageId ?: existingReceived.referenceInternetMessageIds.lastOrNull(),
            )
        } catch (ex: Throwable) {
            log.warn("Outbound hydration for an already-projected inbound email failed: {}", ex.message ?: "Unknown error")
        }
        return
    }

    val receivedEmail = resend.getReceived(data.emailId)
    val inReplyTo = extractMessageIds(getHeader(receivedEmail.headers, "in-reply-to"))
    val references = extractMessageIds(getHeader(receivedEmail.headers, "references"))
    hydrateReferencedOutboundMessages(
        resend,
        parseAddress(data.from.ifEmpty { receivedEmail.from }).address,
        references + inReplyTo,
        inReplyTo.lastOrNull() ?: references.lastOrNull(),
    )
    projectInboundEmail(data, receivedEmail, configuredReplyTo)
}

private fun emailEventRow(envelope: WebhookEnvelope, data: EmailEventData, svixId: String) = EmailEventRow(
    svixId = svixId,
    eventType = envelope.type,
    eventCreatedAt = envelope.createdAt,
    emailId = data.emailId,
    fromAddress = data.from,
    toAddresses = data.to,
    subject = data.subject,
    emailCreatedAt = data.createdAt,
    broadcastId = data.broadcastId,
    templateId = data.templateId,
    tagsJson = data.tags?.let {
        compactJson.encodeToString(MapSerializer(String.serializer(), String.serializer()), it)
    },
    bounceType = data.bounce?.type,
    bounceSubType = data.bounce?.subType,
    bounceMessage = data.bounce?.message,
    bounceDiagnosticCode = data.bounce?.diagnosticCode ?: emptyList(),
    deliveryDetail = deliveryDetail(envelope.type, data),
    clickIpAddress = data.click?.ipAddress,
    clickLink = data.click?.link,
    clickTimestamp = data.click?.timestamp,
    clickUserAgent = data.click?.userAgent,
)

private fun Services.insertContactEvent(envelope: WebhookEnvelope, svixId: String) {
    val data = json.decodeFromJsonElement(ContactEventData.serializer(), envelope.data)
    jdbi.h {
        it.insertContactEvent(
            ContactEventRow(
                svixId = svixId,
                eventType = envelope.type,
                eventCreatedAt = envelope.createdAt,
                contactId = data.id,
                audienceId = data.audienceId,
                segmentIds = data.segmentIds,
                email = data.email,
                firstName = data.firstName,
                lastName = data.lastName,
                unsubscribed = data.unsubscribed,
                contactCreatedAt = data.createdAt,
                contactUpdatedAt = data.updatedAt,
            ),
        )
    }
}

private fun Services.insertDomainEvent(envelope: WebhookEnvelope, svixId: String) {
    val data = json.decodeFromJsonElement(DomainEventData.serializer(), envelope.data)
    val recordsJson = compactJson.encodeToString(
        kotlinx.serialization.builtins.ListSerializer(com.castab.resend.email.DomainRecord.serializer()),
        data.records,
    )
    jdbi.h {
        it.insertDomainEvent(
            DomainEventRow(
                svixId = svixId,
                eventType = envelope.type,
                eventCreatedAt = envelope.createdAt,
                domainId = data.id,
                name = data.name,
                status = data.status,
                region = data.region,
                domainCreatedAt = data.createdAt,
                recordsJson = recordsJson,
            ),
        )
    }
}
