package com.castab.resend.data

import com.castab.resend.domain.EmailDeliveryState
import com.castab.resend.email.StoredDeliveryEvent
import org.jdbi.v3.core.Handle
import java.time.OffsetDateTime

data class EmailEventRow(
    val svixId: String,
    val eventType: String,
    val eventCreatedAt: String,
    val emailId: String,
    val fromAddress: String,
    val toAddresses: List<String>,
    val subject: String,
    val emailCreatedAt: String,
    val broadcastId: String?,
    val templateId: String?,
    val tagsJson: String?,
    val bounceType: String?,
    val bounceSubType: String?,
    val bounceMessage: String?,
    val bounceDiagnosticCode: List<String>,
    val deliveryDetail: String?,
    val clickIpAddress: String?,
    val clickLink: String?,
    val clickTimestamp: String?,
    val clickUserAgent: String?,
)

data class ContactEventRow(
    val svixId: String,
    val eventType: String,
    val eventCreatedAt: String,
    val contactId: String,
    val audienceId: String?,
    val segmentIds: List<String>,
    val email: String,
    val firstName: String?,
    val lastName: String?,
    val unsubscribed: Boolean,
    val contactCreatedAt: String,
    val contactUpdatedAt: String,
)

data class DomainEventRow(
    val svixId: String,
    val eventType: String,
    val eventCreatedAt: String,
    val domainId: String,
    val name: String,
    val status: String,
    val region: String,
    val domainCreatedAt: String,
    val recordsJson: String,
)

fun Handle.findEmailEventIdBySvixId(svixId: String): String? =
    createQuery("SELECT email_id FROM resend_wh_emails WHERE svix_id = :svixId")
        .bind("svixId", svixId).mapTo(String::class.java).findOne().orElse(null)

fun Handle.insertEmailEvent(row: EmailEventRow) {
    createUpdate(
        """
        INSERT INTO resend_wh_emails
          (svix_id, event_type, event_created_at, email_id, from_address, to_addresses, subject,
           email_created_at, broadcast_id, template_id, tags, bounce_type, bounce_sub_type,
           bounce_message, bounce_diagnostic_code, delivery_detail, click_ip_address, click_link,
           click_timestamp, click_user_agent)
        VALUES
          (:svixId, :eventType, CAST(:eventCreatedAt AS timestamptz), :emailId, :fromAddress,
           :toAddresses, :subject, CAST(:emailCreatedAt AS timestamptz), :broadcastId, :templateId,
           CAST(:tags AS jsonb), :bounceType, :bounceSubType, :bounceMessage, :bounceDiagnosticCode,
           :deliveryDetail, :clickIpAddress, :clickLink, CAST(:clickTimestamp AS timestamptz),
           :clickUserAgent)
        ON CONFLICT (svix_id) DO NOTHING
        """.trimIndent(),
    )
        .bind("svixId", row.svixId)
        .bind("eventType", row.eventType)
        .bind("eventCreatedAt", row.eventCreatedAt)
        .bind("emailId", row.emailId)
        .bind("fromAddress", row.fromAddress)
        .bindArray("toAddresses", String::class.java, row.toAddresses)
        .bind("subject", row.subject)
        .bind("emailCreatedAt", row.emailCreatedAt)
        .bind("broadcastId", row.broadcastId)
        .bind("templateId", row.templateId)
        .bind("tags", row.tagsJson)
        .bind("bounceType", row.bounceType)
        .bind("bounceSubType", row.bounceSubType)
        .bind("bounceMessage", row.bounceMessage)
        .bindArray("bounceDiagnosticCode", String::class.java, row.bounceDiagnosticCode)
        .bind("deliveryDetail", row.deliveryDetail)
        .bind("clickIpAddress", row.clickIpAddress)
        .bind("clickLink", row.clickLink)
        .bind("clickTimestamp", row.clickTimestamp)
        .bind("clickUserAgent", row.clickUserAgent)
        .execute()
}

fun Handle.insertContactEvent(row: ContactEventRow) {
    createUpdate(
        """
        INSERT INTO resend_wh_contacts
          (svix_id, event_type, event_created_at, contact_id, audience_id, segment_ids, email,
           first_name, last_name, unsubscribed, contact_created_at, contact_updated_at)
        VALUES
          (:svixId, :eventType, CAST(:eventCreatedAt AS timestamptz), :contactId, :audienceId,
           :segmentIds, :email, :firstName, :lastName, :unsubscribed,
           CAST(:contactCreatedAt AS timestamptz), CAST(:contactUpdatedAt AS timestamptz))
        ON CONFLICT (svix_id) DO NOTHING
        """.trimIndent(),
    )
        .bind("svixId", row.svixId)
        .bind("eventType", row.eventType)
        .bind("eventCreatedAt", row.eventCreatedAt)
        .bind("contactId", row.contactId)
        .bind("audienceId", row.audienceId)
        .bindArray("segmentIds", String::class.java, row.segmentIds)
        .bind("email", row.email)
        .bind("firstName", row.firstName)
        .bind("lastName", row.lastName)
        .bind("unsubscribed", row.unsubscribed)
        .bind("contactCreatedAt", row.contactCreatedAt)
        .bind("contactUpdatedAt", row.contactUpdatedAt)
        .execute()
}

fun Handle.insertDomainEvent(row: DomainEventRow) {
    createUpdate(
        """
        INSERT INTO resend_wh_domains
          (svix_id, event_type, event_created_at, domain_id, name, status, region,
           domain_created_at, records)
        VALUES
          (:svixId, :eventType, CAST(:eventCreatedAt AS timestamptz), :domainId, :name, :status,
           :region, CAST(:domainCreatedAt AS timestamptz), CAST(:records AS jsonb))
        ON CONFLICT (svix_id) DO NOTHING
        """.trimIndent(),
    )
        .bind("svixId", row.svixId)
        .bind("eventType", row.eventType)
        .bind("eventCreatedAt", row.eventCreatedAt)
        .bind("domainId", row.domainId)
        .bind("name", row.name)
        .bind("status", row.status)
        .bind("region", row.region)
        .bind("domainCreatedAt", row.domainCreatedAt)
        .bind("records", row.recordsJson)
        .execute()
}

fun Handle.findDeliveryEvents(emailId: String, types: List<String>): List<StoredDeliveryEvent> =
    createQuery(
        """
        SELECT id::text AS id, event_type, event_created_at, webhook_received_at, delivery_detail
        FROM resend_wh_emails
        WHERE email_id = :emailId AND event_type = ANY(:types)
        ORDER BY event_created_at ASC, webhook_received_at ASC, id ASC
        """.trimIndent(),
    )
        .bind("emailId", emailId)
        .bindArray("types", String::class.java, types)
        .map { rs, _ ->
            StoredDeliveryEvent(
                id = rs.getString("id"),
                eventType = rs.getString("event_type"),
                eventCreatedAt = rs.getObject("event_created_at", OffsetDateTime::class.java),
                webhookReceivedAt = rs.getObject("webhook_received_at", OffsetDateTime::class.java),
                deliveryDetail = rs.getString("delivery_detail"),
            )
        }
        .list()

fun Handle.updateOutboundDeliveryState(
    resendEmailId: String,
    state: EmailDeliveryState,
    detail: String?,
    updatedAt: OffsetDateTime,
    deliveredAt: OffsetDateTime?,
) {
    createUpdate(
        """
        UPDATE email_messages
        SET delivery_state = CAST(:state AS "EmailDeliveryState"), delivery_state_detail = :detail,
            delivery_state_updated_at = :updatedAt, delivered_at = :deliveredAt, updated_at = now()
        WHERE direction = 'OUTBOUND' AND resend_email_id = :id
        """.trimIndent(),
    )
        .bind("id", resendEmailId)
        .bind("state", state.name)
        .bind("detail", detail)
        .bind("updatedAt", updatedAt)
        .bind("deliveredAt", deliveredAt)
        .execute()
}
