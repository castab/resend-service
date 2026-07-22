package com.castab.resend.domain

import java.time.OffsetDateTime

enum class EmailDirection { INBOUND, OUTBOUND }

enum class EmailMessageState { RECEIVED, PENDING, ACCEPTED, FAILED, INDETERMINATE }

enum class EmailDeliveryState {
    UNKNOWN,
    DELIVERED,
    DELIVERY_DELAYED,
    BOUNCED,
    COMPLAINED,
    SUPPRESSED,
    FAILED,
}

/** Wire form of an enum: lower-cased name (e.g. OUTBOUND -> "outbound", DELIVERY_DELAYED -> "delivery_delayed"). */
fun Enum<*>.wire(): String = name.lowercase()

data class EmailConversation(
    val id: String,
    val routingToken: String,
    val topicType: String?,
    val externalTopicId: String?,
    val title: String,
    val subject: String,
    val participantAddress: String,
    val participantName: String?,
    val lastMessageAt: OffsetDateTime,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

data class EmailMessage(
    val id: String,
    val conversationId: String,
    val parentMessageId: String?,
    val direction: EmailDirection,
    val state: EmailMessageState,
    val stateDetail: String?,
    val deliveryState: EmailDeliveryState?,
    val deliveryStateDetail: String?,
    val deliveryStateUpdatedAt: OffsetDateTime?,
    val deliveredAt: OffsetDateTime?,
    val resendEmailId: String?,
    val internetMessageId: String?,
    val inReplyToInternetMessageId: String?,
    val referenceInternetMessageIds: List<String>,
    val fromAddress: String,
    val fromName: String?,
    val toAddress: String,
    val replyToAddress: String?,
    val replyToName: String?,
    val subject: String,
    val textBody: String?,
    val htmlBody: String?,
    val emailCreatedAt: OffsetDateTime,
    val idempotencyKey: String?,
    val requestHash: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)
