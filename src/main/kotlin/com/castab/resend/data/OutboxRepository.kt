package com.castab.resend.data

import com.castab.resend.domain.EmailMessage
import com.castab.resend.domain.EmailMessageState
import org.jdbi.v3.core.Handle
import java.time.OffsetDateTime

data class OutboxBatchRow(val id: String, val attemptCount: Int, val firstAttemptAt: OffsetDateTime?)

fun Handle.insertOutboxEntry(messageId: String) {
    createUpdate("INSERT INTO email_outbox_entries (message_id) VALUES (CAST(:id AS uuid))")
        .bind("id", messageId).execute()
}

fun Handle.deleteOrphanOutboxEntries() {
    createUpdate(
        """
        DELETE FROM email_outbox_entries AS entry
        USING email_messages AS message
        WHERE entry.message_id = message.id AND entry.batch_id IS NULL AND message.state <> 'PENDING'
        """.trimIndent(),
    ).execute()
}

fun Handle.selectRetryableBatchId(now: OffsetDateTime): String? =
    createQuery(
        """
        SELECT id::text FROM email_outbox_batches
        WHERE next_attempt_at <= :now AND (lease_until IS NULL OR lease_until <= :now)
        ORDER BY next_attempt_at, id
        FOR UPDATE SKIP LOCKED
        LIMIT 1
        """.trimIndent(),
    ).bind("now", now).mapTo(String::class.java).findOne().orElse(null)

fun Handle.selectUnbatchedMessageIds(limit: Int): List<String> =
    createQuery(
        """
        SELECT entry.message_id::text
        FROM email_outbox_entries AS entry
        JOIN email_messages AS message ON message.id = entry.message_id
        WHERE entry.batch_id IS NULL AND message.state = 'PENDING'
        ORDER BY entry.queued_at, entry.message_id
        FOR UPDATE OF entry SKIP LOCKED
        LIMIT :limit
        """.trimIndent(),
    ).bind("limit", limit).mapTo(String::class.java).list()

fun Handle.createOutboxBatch(leaseToken: String, leaseUntil: OffsetDateTime, firstAttemptAt: OffsetDateTime): String =
    createQuery(
        """
        INSERT INTO email_outbox_batches (lease_token, lease_until, first_attempt_at, attempt_count, updated_at)
        VALUES (CAST(:leaseToken AS uuid), :leaseUntil, :firstAttemptAt, 1, now())
        RETURNING id::text
        """.trimIndent(),
    )
        .bind("leaseToken", leaseToken)
        .bind("leaseUntil", leaseUntil)
        .bind("firstAttemptAt", firstAttemptAt)
        .mapTo(String::class.java)
        .one()

fun Handle.assignEntriesToBatch(batchId: String, orderedMessageIds: List<String>) {
    if (orderedMessageIds.isEmpty()) return
    val rows = orderedMessageIds.indices.joinToString(", ") { "(CAST(:m$it AS uuid), CAST(:p$it AS integer))" }
    val update = createUpdate(
        """
        UPDATE email_outbox_entries AS entry
        SET batch_id = CAST(:batchId AS uuid), batch_position = assignment.position
        FROM (VALUES $rows) AS assignment(message_id, position)
        WHERE entry.message_id = assignment.message_id AND entry.batch_id IS NULL
        """.trimIndent(),
    ).bind("batchId", batchId)
    orderedMessageIds.forEachIndexed { index, id ->
        update.bind("m$index", id).bind("p$index", index)
    }
    update.execute()
}

fun Handle.reclaimBatch(batchId: String, leaseToken: String, leaseUntil: OffsetDateTime) {
    createUpdate(
        """
        UPDATE email_outbox_batches
        SET lease_token = CAST(:leaseToken AS uuid), lease_until = :leaseUntil,
            attempt_count = attempt_count + 1, last_error_code = NULL, updated_at = now()
        WHERE id = CAST(:id AS uuid)
        """.trimIndent(),
    ).bind("id", batchId).bind("leaseToken", leaseToken).bind("leaseUntil", leaseUntil).execute()
}

fun Handle.findOutboxBatch(batchId: String): OutboxBatchRow? =
    createQuery("SELECT id::text AS id, attempt_count, first_attempt_at FROM email_outbox_batches WHERE id = CAST(:id AS uuid)")
        .bind("id", batchId)
        .map { rs, _ ->
            OutboxBatchRow(
                rs.getString("id"),
                rs.getInt("attempt_count"),
                rs.getObject("first_attempt_at", OffsetDateTime::class.java),
            )
        }
        .findOne().orElse(null)

fun Handle.findBatchMessagesOrdered(batchId: String): List<EmailMessage> =
    createQuery(
        """
        SELECT m.* FROM email_outbox_entries e
        JOIN email_messages m ON m.id = e.message_id
        WHERE e.batch_id = CAST(:id AS uuid)
        ORDER BY e.batch_position ASC
        """.trimIndent(),
    ).bind("id", batchId).map(MessageMapper).list()

fun Handle.isBatchLeaseOwned(batchId: String, leaseToken: String): Boolean =
    createQuery("SELECT 1 FROM email_outbox_batches WHERE id = CAST(:id AS uuid) AND lease_token = CAST(:token AS uuid)")
        .bind("id", batchId).bind("token", leaseToken).mapTo(Int::class.java).findOne().isPresent

fun Handle.deleteOwnedBatch(batchId: String, leaseToken: String): Int =
    createUpdate("DELETE FROM email_outbox_batches WHERE id = CAST(:id AS uuid) AND lease_token = CAST(:token AS uuid)")
        .bind("id", batchId).bind("token", leaseToken).execute()

fun Handle.scheduleBatchRetry(batchId: String, leaseToken: String, nextAttemptAt: OffsetDateTime, errorCode: String): Int =
    createUpdate(
        """
        UPDATE email_outbox_batches
        SET next_attempt_at = :next, lease_token = NULL, lease_until = NULL, last_error_code = :code, updated_at = now()
        WHERE id = CAST(:id AS uuid) AND lease_token = CAST(:token AS uuid)
        """.trimIndent(),
    ).bind("id", batchId).bind("token", leaseToken).bind("next", nextAttemptAt).bind("code", errorCode.take(64)).execute()

fun Handle.markBatchMessagesState(ids: List<String>, state: EmailMessageState, detail: String) {
    if (ids.isEmpty()) return
    createUpdate(
        """
        UPDATE email_messages
        SET state = CAST(:state AS "EmailMessageState"), state_detail = :detail, updated_at = now()
        WHERE id = ANY(:ids) AND state = 'PENDING'
        """.trimIndent(),
    )
        .bind("state", state.name)
        .bind("detail", detail)
        .bindArray("ids", java.util.UUID::class.java, ids.map(java.util.UUID::fromString))
        .execute()
}

/** Sets ACCEPTED + resend id for each still-PENDING batch message; returns the number updated. */
fun Handle.acceptBatchMessages(orderedIds: List<String>, resendIds: List<String>): Int {
    if (orderedIds.isEmpty()) return 0
    val rows = orderedIds.indices.joinToString(", ") { "(CAST(:m$it AS uuid), CAST(:r$it AS text))" }
    val update = createUpdate(
        """
        UPDATE email_messages AS message
        SET state = 'ACCEPTED', state_detail = NULL, delivery_state = 'UNKNOWN',
            resend_email_id = accepted.resend_email_id, updated_at = now()
        FROM (VALUES $rows) AS accepted(message_id, resend_email_id)
        WHERE message.id = accepted.message_id AND message.state = 'PENDING'
        """.trimIndent(),
    )
    orderedIds.forEachIndexed { index, id ->
        update.bind("m$index", id).bind("r$index", resendIds[index])
    }
    return update.execute()
}
