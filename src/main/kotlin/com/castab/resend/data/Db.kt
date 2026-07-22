package com.castab.resend.data

import com.castab.resend.domain.EmailConversation
import com.castab.resend.domain.EmailDeliveryState
import com.castab.resend.domain.EmailDirection
import com.castab.resend.domain.EmailMessage
import com.castab.resend.domain.EmailMessageState
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.mapper.RowMapper
import org.jdbi.v3.core.statement.StatementContext
import org.jdbi.v3.core.transaction.TransactionIsolationLevel
import java.sql.ResultSet
import java.sql.SQLException
import java.time.OffsetDateTime
import javax.sql.DataSource

fun buildJdbi(source: DataSource): Jdbi = Jdbi.create(source).also {
    it.registerRowMapper(ConversationMapper)
    it.registerRowMapper(MessageMapper)
}

/** Runs [block] in a transaction at the given isolation level. */
fun <T> Jdbi.tx(
    level: TransactionIsolationLevel = TransactionIsolationLevel.READ_COMMITTED,
    block: (Handle) -> T,
): T = inTransaction<T, RuntimeException>(level) { handle -> block(handle) }

/** Takes a session-scoped advisory lock keyed by `hashtext(key)` for the current transaction. */
fun Handle.advisoryXactLock(key: String) {
    createQuery("SELECT pg_advisory_xact_lock(hashtext(:k))::text")
        .bind("k", key)
        .mapTo(String::class.java)
        .one()
}

/**
 * Runs a serializable transaction, retrying up to [attempts] times on serialization/deadlock failures
 * (SQLSTATE 40001 / 40P01), mirroring the Express Prisma `P2034` retry loop.
 */
fun <T> Jdbi.serializableWithRetry(attempts: Int = 3, block: (Handle) -> T): T {
    var last: RuntimeException? = null
    repeat(attempts) {
        try {
            return tx(TransactionIsolationLevel.SERIALIZABLE, block)
        } catch (e: RuntimeException) {
            if (isSerializationFailure(e)) {
                last = e
            } else {
                throw e
            }
        }
    }
    throw last ?: IllegalStateException("Serializable transaction retry limit exhausted")
}

fun isSerializationFailure(e: Throwable?): Boolean = sqlStates(e).any { it == "40001" || it == "40P01" }

fun isUniqueViolation(e: Throwable?): Boolean = sqlStates(e).any { it == "23505" }

private fun sqlStates(e: Throwable?): Sequence<String> = generateSequence(e) { it.cause }
    .mapNotNull { (it as? SQLException)?.sqlState }

private fun ResultSet.offsetDateTime(column: String): OffsetDateTime? =
    getObject(column, OffsetDateTime::class.java)

private fun ResultSet.stringArray(column: String): List<String> {
    val array = getArray(column) ?: return emptyList()
    @Suppress("UNCHECKED_CAST")
    return (array.array as Array<Any?>).mapNotNull { it as String? }
}

object ConversationMapper : RowMapper<EmailConversation> {
    override fun map(rs: ResultSet, ctx: StatementContext): EmailConversation = EmailConversation(
        id = rs.getString("id"),
        routingToken = rs.getString("routing_token"),
        topicType = rs.getString("topic_type"),
        externalTopicId = rs.getString("external_topic_id"),
        title = rs.getString("title"),
        subject = rs.getString("subject"),
        participantAddress = rs.getString("participant_address"),
        participantName = rs.getString("participant_name"),
        lastMessageAt = rs.offsetDateTime("last_message_at")!!,
        createdAt = rs.offsetDateTime("created_at")!!,
        updatedAt = rs.offsetDateTime("updated_at")!!,
    )
}

object MessageMapper : RowMapper<EmailMessage> {
    override fun map(rs: ResultSet, ctx: StatementContext): EmailMessage = EmailMessage(
        id = rs.getString("id"),
        conversationId = rs.getString("conversation_id"),
        parentMessageId = rs.getString("parent_message_id"),
        direction = EmailDirection.valueOf(rs.getString("direction")),
        state = EmailMessageState.valueOf(rs.getString("state")),
        stateDetail = rs.getString("state_detail"),
        deliveryState = rs.getString("delivery_state")?.let { EmailDeliveryState.valueOf(it) },
        deliveryStateDetail = rs.getString("delivery_state_detail"),
        deliveryStateUpdatedAt = rs.offsetDateTime("delivery_state_updated_at"),
        deliveredAt = rs.offsetDateTime("delivered_at"),
        resendEmailId = rs.getString("resend_email_id"),
        internetMessageId = rs.getString("internet_message_id"),
        inReplyToInternetMessageId = rs.getString("in_reply_to_internet_message_id"),
        referenceInternetMessageIds = rs.stringArray("reference_internet_message_ids"),
        fromAddress = rs.getString("from_address"),
        fromName = rs.getString("from_name"),
        toAddress = rs.getString("to_address"),
        replyToAddress = rs.getString("reply_to_address"),
        replyToName = rs.getString("reply_to_name"),
        subject = rs.getString("subject"),
        textBody = rs.getString("text_body"),
        htmlBody = rs.getString("html_body"),
        emailCreatedAt = rs.offsetDateTime("email_created_at")!!,
        idempotencyKey = rs.getString("idempotency_key"),
        requestHash = rs.getString("request_hash"),
        createdAt = rs.offsetDateTime("created_at")!!,
        updatedAt = rs.offsetDateTime("updated_at")!!,
    )
}
