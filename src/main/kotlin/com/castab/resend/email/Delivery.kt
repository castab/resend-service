package com.castab.resend.email

import com.castab.resend.domain.EmailDeliveryState
import java.time.OffsetDateTime

val DELIVERY_EVENT_TYPES = listOf(
    "email.delivered",
    "email.delivery_delayed",
    "email.bounced",
    "email.complained",
    "email.suppressed",
    "email.failed",
)

private val DELIVERY_STATE_BY_EVENT_TYPE = mapOf(
    "email.delivered" to EmailDeliveryState.DELIVERED,
    "email.delivery_delayed" to EmailDeliveryState.DELIVERY_DELAYED,
    "email.bounced" to EmailDeliveryState.BOUNCED,
    "email.complained" to EmailDeliveryState.COMPLAINED,
    "email.suppressed" to EmailDeliveryState.SUPPRESSED,
    "email.failed" to EmailDeliveryState.FAILED,
)

fun isDeliveryEventType(type: String): Boolean = DELIVERY_STATE_BY_EVENT_TYPE.containsKey(type)

data class StoredDeliveryEvent(
    val id: String,
    val eventType: String,
    val eventCreatedAt: OffsetDateTime,
    val webhookReceivedAt: OffsetDateTime,
    val deliveryDetail: String?,
)

data class ProjectedDeliveryState(
    val state: EmailDeliveryState,
    val detail: String?,
    val eventCreatedAt: OffsetDateTime,
)

data class DeliveryProjection(val current: ProjectedDeliveryState?, val deliveredAt: OffsetDateTime?)

private fun earlier(left: OffsetDateTime?, right: OffsetDateTime): OffsetDateTime =
    if (left != null && !left.isAfter(right)) left else right

/**
 * Reduces the ordered stored delivery events into the current projected state, matching the Express
 * `reduceDeliveryEvents`: `delivery_delayed` never overwrites a non-delayed state, and `delivered`
 * tracks the earliest delivered timestamp.
 */
fun reduceDeliveryEvents(events: List<StoredDeliveryEvent>): DeliveryProjection {
    var deliveredAt: OffsetDateTime? = null
    var current: ProjectedDeliveryState? = null
    for (event in events) {
        val state = DELIVERY_STATE_BY_EVENT_TYPE[event.eventType] ?: continue
        if (state == EmailDeliveryState.DELIVERED) {
            deliveredAt = earlier(deliveredAt, event.eventCreatedAt)
        }
        if (state == EmailDeliveryState.DELIVERY_DELAYED &&
            current != null &&
            current.state != EmailDeliveryState.DELIVERY_DELAYED
        ) {
            continue
        }
        current = ProjectedDeliveryState(state, event.deliveryDetail, event.eventCreatedAt)
    }
    return DeliveryProjection(current, deliveredAt)
}
