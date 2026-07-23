package com.castab.resend.data

import com.castab.resend.domain.EmailConversation
import com.castab.resend.domain.EmailDeliveryState
import com.castab.resend.domain.EmailDirection
import com.castab.resend.domain.EmailMessage
import com.castab.resend.domain.EmailMessageState
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import java.time.OffsetDateTime

/** Opens a short auto-committing handle for standalone reads/writes outside an explicit transaction. */
fun <T> Jdbi.h(block: (Handle) -> T): T = withHandle<T, RuntimeException> { block(it) }

data class NewConversation(
    val routingToken: String? = null,
    val topicType: String?,
    val externalTopicId: String?,
    val title: String,
    val subject: String,
    val participantAddress: String,
    val participantName: String?,
    val lastMessageAt: OffsetDateTime,
)

data class NewMessage(
    val conversationId: String,
    val parentMessageId: String? = null,
    val direction: EmailDirection,
    val state: EmailMessageState,
    val stateDetail: String? = null,
    val deliveryState: EmailDeliveryState? = null,
    val resendEmailId: String? = null,
    val internetMessageId: String? = null,
    val inReplyToInternetMessageId: String? = null,
    val referenceInternetMessageIds: List<String> = emptyList(),
    val fromAddress: String,
    val fromName: String?,
    val toAddress: String,
    val replyToAddress: String?,
    val replyToName: String?,
    val subject: String,
    val textBody: String?,
    val htmlBody: String?,
    val emailCreatedAt: OffsetDateTime,
    val idempotencyKey: String? = null,
    val requestHash: String? = null,
)

// ---------------------------------------------------------------------------
// Conversations
// ---------------------------------------------------------------------------

fun Handle.insertConversation(c: NewConversation): EmailConversation =
    createQuery(
        """
        INSERT INTO email_conversations
          (routing_token, topic_type, external_topic_id, title, subject,
           participant_address, participant_name, last_message_at, updated_at)
        VALUES
          (COALESCE(CAST(:routingToken AS uuid), gen_random_uuid()), :topicType, :externalTopicId,
           :title, :subject, :participantAddress, :participantName, :lastMessageAt, now())
        RETURNING *
        """.trimIndent(),
    )
        .bind("routingToken", c.routingToken)
        .bind("topicType", c.topicType)
        .bind("externalTopicId", c.externalTopicId)
        .bind("title", c.title)
        .bind("subject", c.subject)
        .bind("participantAddress", c.participantAddress)
        .bind("participantName", c.participantName)
        .bind("lastMessageAt", c.lastMessageAt)
        .map(ConversationMapper)
        .one()

fun Handle.findConversationById(id: String): EmailConversation? =
    createQuery("SELECT * FROM email_conversations WHERE id = CAST(:id AS uuid)")
        .bind("id", id)
        .map(ConversationMapper)
        .findOne()
        .orElse(null)

fun Handle.findConversationByTopic(topicType: String, externalTopicId: String): EmailConversation? =
    createQuery(
        "SELECT * FROM email_conversations WHERE topic_type = :t AND external_topic_id = :e",
    )
        .bind("t", topicType)
        .bind("e", externalTopicId)
        .map(ConversationMapper)
        .findOne()
        .orElse(null)

fun Handle.findConversationsByRoutingTokens(tokens: List<String>, limit: Int = 2): List<EmailConversation> {
    if (tokens.isEmpty()) return emptyList()
    return createQuery(
        "SELECT * FROM email_conversations WHERE routing_token = ANY(:tokens) LIMIT :limit",
    )
        .bindArray("tokens", java.util.UUID::class.java, tokens.map(java.util.UUID::fromString))
        .bind("limit", limit)
        .map(ConversationMapper)
        .list()
}

fun Handle.listUnassignedConversations(
    before: Pair<OffsetDateTime, String>?,
    limit: Int,
): List<EmailConversation> {
    val query = if (before == null) {
        createQuery(
            """
            SELECT * FROM email_conversations
            WHERE topic_type IS NULL AND external_topic_id IS NULL
            ORDER BY last_message_at DESC, id DESC
            LIMIT :limit
            """.trimIndent(),
        )
    } else {
        createQuery(
            """
            SELECT * FROM email_conversations
            WHERE topic_type IS NULL AND external_topic_id IS NULL
              AND (last_message_at < :ts OR (last_message_at = :ts AND id < CAST(:id AS uuid)))
            ORDER BY last_message_at DESC, id DESC
            LIMIT :limit
            """.trimIndent(),
        ).bind("ts", before.first).bind("id", before.second)
    }
    return query.bind("limit", limit).map(ConversationMapper).list()
}

/** Assigns a topic to an as-yet-unassigned conversation; returns the number of rows updated (0 or 1). */
fun Handle.assignConversationTopic(id: String, type: String, externalId: String, title: String): Int =
    createUpdate(
        """
        UPDATE email_conversations
        SET topic_type = :type, external_topic_id = :externalId, title = :title, updated_at = now()
        WHERE id = CAST(:id AS uuid) AND topic_type IS NULL AND external_topic_id IS NULL
        """.trimIndent(),
    )
        .bind("id", id)
        .bind("type", type)
        .bind("externalId", externalId)
        .bind("title", title)
        .execute()

fun Handle.updateConversationReopen(
    id: String,
    title: String,
    subject: String,
    participantAddress: String,
    participantName: String?,
    lastMessageAt: OffsetDateTime,
) {
    createUpdate(
        """
        UPDATE email_conversations
        SET title = :title, subject = :subject, participant_address = :participantAddress,
            participant_name = :participantName, last_message_at = :lastMessageAt, updated_at = now()
        WHERE id = CAST(:id AS uuid)
        """.trimIndent(),
    )
        .bind("id", id)
        .bind("title", title)
        .bind("subject", subject)
        .bind("participantAddress", participantAddress)
        .bind("participantName", participantName)
        .bind("lastMessageAt", lastMessageAt)
        .execute()
}

fun Handle.setConversationLastMessageAt(id: String, ts: OffsetDateTime) {
    createUpdate(
        "UPDATE email_conversations SET last_message_at = :ts, updated_at = now() WHERE id = CAST(:id AS uuid)",
    ).bind("id", id).bind("ts", ts).execute()
}

fun Handle.bumpConversationLastMessageAt(id: String, ts: OffsetDateTime) {
    createUpdate(
        """
        UPDATE email_conversations
        SET last_message_at = GREATEST(last_message_at, :ts), updated_at = now()
        WHERE id = CAST(:id AS uuid)
        """.trimIndent(),
    ).bind("id", id).bind("ts", ts).execute()
}

fun Handle.deleteConversations(ids: List<String>) {
    if (ids.isEmpty()) return
    createUpdate("DELETE FROM email_conversations WHERE id = ANY(:ids)")
        .bindArray("ids", java.util.UUID::class.java, ids.map(java.util.UUID::fromString))
        .execute()
}

// ---------------------------------------------------------------------------
// Messages
// ---------------------------------------------------------------------------

fun Handle.insertMessage(m: NewMessage): EmailMessage =
    createQuery(
        """
        INSERT INTO email_messages
          (conversation_id, parent_message_id, direction, state, state_detail, delivery_state,
           resend_email_id, internet_message_id, in_reply_to_internet_message_id,
           reference_internet_message_ids, from_address, from_name, to_address, reply_to_address,
           reply_to_name, subject, text_body, html_body, email_created_at, idempotency_key,
           request_hash, updated_at)
        VALUES
          (CAST(:conversationId AS uuid), CAST(:parentMessageId AS uuid),
           CAST(:direction AS "EmailDirection"), CAST(:state AS "EmailMessageState"), :stateDetail,
           CAST(:deliveryState AS "EmailDeliveryState"), :resendEmailId, :internetMessageId,
           :inReplyTo, :refs, :fromAddress, :fromName, :toAddress, :replyToAddress, :replyToName,
           :subject, :textBody, :htmlBody, :emailCreatedAt, :idempotencyKey, :requestHash, now())
        RETURNING *
        """.trimIndent(),
    )
        .bind("conversationId", m.conversationId)
        .bind("parentMessageId", m.parentMessageId)
        .bind("direction", m.direction.name)
        .bind("state", m.state.name)
        .bind("stateDetail", m.stateDetail)
        .bind("deliveryState", m.deliveryState?.name)
        .bind("resendEmailId", m.resendEmailId)
        .bind("internetMessageId", m.internetMessageId)
        .bind("inReplyTo", m.inReplyToInternetMessageId)
        .bindArray("refs", String::class.java, m.referenceInternetMessageIds)
        .bind("fromAddress", m.fromAddress)
        .bind("fromName", m.fromName)
        .bind("toAddress", m.toAddress)
        .bind("replyToAddress", m.replyToAddress)
        .bind("replyToName", m.replyToName)
        .bind("subject", m.subject)
        .bind("textBody", m.textBody)
        .bind("htmlBody", m.htmlBody)
        .bind("emailCreatedAt", m.emailCreatedAt)
        .bind("idempotencyKey", m.idempotencyKey)
        .bind("requestHash", m.requestHash)
        .map(MessageMapper)
        .one()

fun Handle.findMessageById(id: String): EmailMessage? =
    createQuery("SELECT * FROM email_messages WHERE id = CAST(:id AS uuid)")
        .bind("id", id).map(MessageMapper).findOne().orElse(null)

fun Handle.findMessageByIdempotencyKey(key: String): EmailMessage? =
    createQuery("SELECT * FROM email_messages WHERE idempotency_key = :key")
        .bind("key", key).map(MessageMapper).findOne().orElse(null)

fun Handle.findMessageByResendEmailId(resendEmailId: String): EmailMessage? =
    createQuery("SELECT * FROM email_messages WHERE resend_email_id = :id")
        .bind("id", resendEmailId).map(MessageMapper).findOne().orElse(null)

fun Handle.findMessageByInternetMessageId(internetMessageId: String): EmailMessage? =
    createQuery("SELECT * FROM email_messages WHERE internet_message_id = :id")
        .bind("id", internetMessageId).map(MessageMapper).findOne().orElse(null)

fun Handle.findConversationsByIds(ids: List<String>): List<EmailConversation> {
    if (ids.isEmpty()) return emptyList()
    return createQuery("SELECT * FROM email_conversations WHERE id = ANY(:ids)")
        .bindArray("ids", java.util.UUID::class.java, ids.map(java.util.UUID::fromString))
        .map(ConversationMapper).list()
}

fun Handle.findMessageInConversation(id: String, conversationId: String): EmailMessage? =
    createQuery(
        "SELECT * FROM email_messages WHERE id = CAST(:id AS uuid) AND conversation_id = CAST(:cid AS uuid)",
    ).bind("id", id).bind("cid", conversationId).map(MessageMapper).findOne().orElse(null)

/** Latest reply-parent candidate: newest message in one of [states] by (email_created_at, id). */
fun Handle.findLatestParentCandidate(conversationId: String, states: List<EmailMessageState>): EmailMessage? =
    createQuery(
        """
        SELECT * FROM email_messages
        WHERE conversation_id = CAST(:cid AS uuid) AND state = ANY(CAST(:states AS "EmailMessageState"[]))
        ORDER BY email_created_at DESC, id DESC
        LIMIT 1
        """.trimIndent(),
    )
        .bind("cid", conversationId)
        .bindArray("states", String::class.java, states.map { it.name })
        .map(MessageMapper).findOne().orElse(null)

fun Handle.findFirstMessageWithStateNot(conversationId: String, state: EmailMessageState): EmailMessage? =
    createQuery(
        """
        SELECT * FROM email_messages
        WHERE conversation_id = CAST(:cid AS uuid) AND state <> CAST(:state AS "EmailMessageState")
        LIMIT 1
        """.trimIndent(),
    ).bind("cid", conversationId).bind("state", state.name).map(MessageMapper).findOne().orElse(null)

/** Conversation page: newest-first, optionally before a cursor message, taking [limit] rows. */
fun Handle.findConversationMessages(
    conversationId: String,
    before: EmailMessage?,
    limit: Int,
): List<EmailMessage> {
    val query = if (before == null) {
        createQuery(
            """
            SELECT * FROM email_messages
            WHERE conversation_id = CAST(:cid AS uuid)
            ORDER BY email_created_at DESC, id DESC
            LIMIT :limit
            """.trimIndent(),
        )
    } else {
        createQuery(
            """
            SELECT * FROM email_messages
            WHERE conversation_id = CAST(:cid AS uuid)
              AND (email_created_at < :ts OR (email_created_at = :ts AND id < CAST(:id AS uuid)))
            ORDER BY email_created_at DESC, id DESC
            LIMIT :limit
            """.trimIndent(),
        ).bind("ts", before.emailCreatedAt).bind("id", before.id)
    }
    return query.bind("cid", conversationId).bind("limit", limit).map(MessageMapper).list()
}

fun Handle.findMessagesByInternetMessageIds(ids: List<String>): List<EmailMessage> {
    if (ids.isEmpty()) return emptyList()
    return createQuery("SELECT * FROM email_messages WHERE internet_message_id = ANY(:ids)")
        .bindArray("ids", String::class.java, ids)
        .map(MessageMapper).list()
}

fun Handle.findWaitingChildren(inReplyToInternetMessageId: String): List<EmailMessage> =
    createQuery(
        """
        SELECT * FROM email_messages
        WHERE parent_message_id IS NULL AND in_reply_to_internet_message_id = :id
        """.trimIndent(),
    ).bind("id", inReplyToInternetMessageId).map(MessageMapper).list()

fun Handle.findFirstOutboundHydrationMessage(
    participantAddress: String,
    internetMessageIds: List<String>,
    preferred: String?,
): EmailMessage? {
    val filter = if (preferred != null) "m.internet_message_id = :preferred" else "m.internet_message_id = ANY(:ids)"
    val query = createQuery(
        """
        SELECT m.* FROM email_messages m
        JOIN email_conversations c ON c.id = m.conversation_id
        WHERE $filter AND lower(c.participant_address) = lower(:participant)
        LIMIT 1
        """.trimIndent(),
    ).bind("participant", participantAddress)
    if (preferred != null) query.bind("preferred", preferred) else query.bindArray("ids", String::class.java, internetMessageIds)
    return query.map(MessageMapper).findOne().orElse(null)
}

fun Handle.findOutboundHydrationCandidates(participantAddress: String, limit: Int): List<EmailMessage> =
    createQuery(
        """
        SELECT m.* FROM email_messages m
        JOIN email_conversations c ON c.id = m.conversation_id
        WHERE m.direction = 'OUTBOUND' AND m.state = 'ACCEPTED'
          AND m.resend_email_id IS NOT NULL AND m.internet_message_id IS NULL
          AND lower(c.participant_address) = lower(:participant)
        ORDER BY m.email_created_at DESC, m.id DESC
        LIMIT :limit
        """.trimIndent(),
    ).bind("participant", participantAddress).bind("limit", limit).map(MessageMapper).list()

fun Handle.maxEmailCreatedAt(conversationId: String): OffsetDateTime? =
    createQuery("SELECT MAX(email_created_at) FROM email_messages WHERE conversation_id = CAST(:cid AS uuid)")
        .bind("cid", conversationId)
        .mapTo(OffsetDateTime::class.java)
        .findOne()
        .orElse(null)

fun Handle.updateMessageAccepted(id: String, resendEmailId: String) {
    createUpdate(
        """
        UPDATE email_messages
        SET state = 'ACCEPTED', state_detail = NULL, delivery_state = 'UNKNOWN',
            resend_email_id = :resend, updated_at = now()
        WHERE id = CAST(:id AS uuid)
        """.trimIndent(),
    ).bind("id", id).bind("resend", resendEmailId).execute()
}

fun Handle.updateMessageStateDetail(id: String, state: EmailMessageState, detail: String?) {
    createUpdate(
        """
        UPDATE email_messages
        SET state = CAST(:state AS "EmailMessageState"), state_detail = :detail, updated_at = now()
        WHERE id = CAST(:id AS uuid)
        """.trimIndent(),
    ).bind("id", id).bind("state", state.name).bind("detail", detail).execute()
}

fun Handle.setMessageInternetMessageId(id: String, internetMessageId: String) {
    createUpdate("UPDATE email_messages SET internet_message_id = :imid, updated_at = now() WHERE id = CAST(:id AS uuid)")
        .bind("id", id).bind("imid", internetMessageId).execute()
}

fun Handle.setMessageInternetMessageIdAndCreatedAt(id: String, internetMessageId: String, emailCreatedAt: OffsetDateTime?) {
    if (emailCreatedAt == null) {
        setMessageInternetMessageId(id, internetMessageId)
        return
    }
    createUpdate(
        """
        UPDATE email_messages
        SET internet_message_id = :imid, email_created_at = :createdAt, updated_at = now()
        WHERE id = CAST(:id AS uuid)
        """.trimIndent(),
    ).bind("id", id).bind("imid", internetMessageId).bind("createdAt", emailCreatedAt).execute()
}

/** Adopts orphan waiting children (parent_message_id null) onto [parentId] within [conversationId]. */
fun Handle.reparentWaitingChildren(inReplyToInternetMessageId: String, conversationId: String, parentId: String): Int =
    createUpdate(
        """
        UPDATE email_messages
        SET parent_message_id = CAST(:parentId AS uuid), updated_at = now()
        WHERE parent_message_id IS NULL AND in_reply_to_internet_message_id = :imid
          AND conversation_id = CAST(:cid AS uuid)
        """.trimIndent(),
    ).bind("parentId", parentId).bind("imid", inReplyToInternetMessageId).bind("cid", conversationId).execute()

fun Handle.reparentSpecificChildren(childIds: List<String>, conversationId: String, parentId: String) {
    if (childIds.isEmpty()) return
    createUpdate(
        """
        UPDATE email_messages
        SET parent_message_id = CAST(:parentId AS uuid), updated_at = now()
        WHERE id = ANY(:ids) AND conversation_id = CAST(:cid AS uuid) AND parent_message_id IS NULL
        """.trimIndent(),
    )
        .bind("parentId", parentId)
        .bindArray("ids", java.util.UUID::class.java, childIds.map(java.util.UUID::fromString))
        .bind("cid", conversationId)
        .execute()
}

fun Handle.reassignMessagesToConversation(fromConversationIds: List<String>, toConversationId: String) {
    if (fromConversationIds.isEmpty()) return
    createUpdate(
        """
        UPDATE email_messages
        SET conversation_id = CAST(:to AS uuid), updated_at = now()
        WHERE conversation_id = ANY(:from)
        """.trimIndent(),
    )
        .bind("to", toConversationId)
        .bindArray("from", java.util.UUID::class.java, fromConversationIds.map(java.util.UUID::fromString))
        .execute()
}
