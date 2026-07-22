package com.castab.resend.email

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class WebhookTag(val name: String, val value: String)

@Serializable
data class BounceData(
    val diagnosticCode: List<String> = emptyList(),
    val message: String? = null,
    val subType: String? = null,
    val type: String? = null,
)

@Serializable
data class ClickData(
    val ipAddress: String? = null,
    val link: String? = null,
    val timestamp: String? = null,
    val userAgent: String? = null,
)

@Serializable
data class FailedData(val reason: String? = null)

@Serializable
data class SuppressedData(val message: String? = null, val type: String? = null)

@Serializable
data class EmailEventData(
    @SerialName("email_id") val emailId: String,
    val from: String,
    val to: List<String> = emptyList(),
    val subject: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("broadcast_id") val broadcastId: String? = null,
    @SerialName("template_id") val templateId: String? = null,
    val tags: List<WebhookTag>? = null,
    val bounce: BounceData? = null,
    val click: ClickData? = null,
    val failed: FailedData? = null,
    val suppressed: SuppressedData? = null,
    @SerialName("message_id") val messageId: String? = null,
    val cc: List<String>? = null,
    val bcc: List<String>? = null,
    @SerialName("received_for") val receivedFor: List<String>? = null,
)

@Serializable
data class ContactEventData(
    val id: String,
    @SerialName("audience_id") val audienceId: String? = null,
    @SerialName("segment_ids") val segmentIds: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val email: String,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    val unsubscribed: Boolean = false,
)

@Serializable
data class DomainRecord(
    val record: String,
    val name: String,
    val type: String,
    val value: String,
    val ttl: String,
    val status: String,
    val priority: Int? = null,
)

@Serializable
data class DomainEventData(
    val id: String,
    val name: String,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    val region: String,
    val records: List<DomainRecord> = emptyList(),
)

/** Raw webhook envelope; `data` is decoded to the specific event data once the category is known. */
@Serializable
data class WebhookEnvelope(
    val type: String,
    @SerialName("created_at") val createdAt: String,
    val data: JsonObject,
)

fun isEmailEvent(type: String): Boolean = type.startsWith("email.")
fun isContactEvent(type: String): Boolean = type.startsWith("contact.")
fun isDomainEvent(type: String): Boolean = type.startsWith("domain.")

/** Human-readable delivery detail for the stored webhook row (mirrors `getDeliveryDetail`). */
fun deliveryDetail(type: String, data: EmailEventData): String? = when (type) {
    "email.bounced" -> data.bounce?.message
        ?: data.bounce?.diagnosticCode?.takeIf { it.isNotEmpty() }?.joinToString("\n")
        ?: data.bounce?.type
    "email.failed" -> data.failed?.reason
    "email.suppressed" -> data.suppressed?.message ?: data.suppressed?.type
    else -> null
}
