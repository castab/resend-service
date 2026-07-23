package com.castab.resend.service

import com.castab.resend.data.NewConversation
import com.castab.resend.data.NewMessage
import com.castab.resend.data.advisoryXactLock
import com.castab.resend.data.deleteConversations
import com.castab.resend.data.findConversationById
import com.castab.resend.data.findConversationsByIds
import com.castab.resend.data.findConversationsByRoutingTokens
import com.castab.resend.data.findFirstOutboundHydrationMessage
import com.castab.resend.data.findMessageById
import com.castab.resend.data.findMessageByInternetMessageId
import com.castab.resend.data.findMessageByResendEmailId
import com.castab.resend.data.findMessagesByInternetMessageIds
import com.castab.resend.data.findOutboundHydrationCandidates
import com.castab.resend.data.findWaitingChildren
import com.castab.resend.data.h
import com.castab.resend.data.insertConversation
import com.castab.resend.data.insertMessage
import com.castab.resend.data.isSerializationFailure
import com.castab.resend.data.isUniqueViolation
import com.castab.resend.data.maxEmailCreatedAt
import com.castab.resend.data.reassignMessagesToConversation
import com.castab.resend.data.reparentSpecificChildren
import com.castab.resend.data.reparentWaitingChildren
import com.castab.resend.data.setConversationLastMessageAt
import com.castab.resend.data.setMessageInternetMessageIdAndCreatedAt
import com.castab.resend.data.tx
import com.castab.resend.domain.EmailConversation
import com.castab.resend.domain.EmailDirection
import com.castab.resend.domain.EmailMessage
import com.castab.resend.domain.EmailMessageState
import com.castab.resend.email.EmailEventData
import com.castab.resend.email.ResendEmail
import com.castab.resend.email.ResendEmailClient
import com.castab.resend.email.extractMessageIds
import com.castab.resend.email.extractRoutingTokens
import com.castab.resend.email.getHeader
import com.castab.resend.email.isValidInternetMessageId
import com.castab.resend.email.normalizeSubject
import com.castab.resend.email.parseAddress
import org.jdbi.v3.core.transaction.TransactionIsolationLevel
import java.time.OffsetDateTime

private const val MAX_OUTBOUND_HYDRATION_CANDIDATES = 10
private const val OUTBOUND_HYDRATION_RETRY_WINDOW_MS = 24L * 60 * 60 * 1000

fun Services.recordOutboundInternetMessageId(
    messageId: String,
    internetMessageId: String,
    emailCreatedAt: OffsetDateTime?,
): EmailMessage = jdbi.tx(TransactionIsolationLevel.SERIALIZABLE) { h ->
    h.advisoryXactLock(internetMessageId)
    val outbound = h.findMessageById(messageId) ?: throw IllegalStateException("Message $messageId not found")
    if (outbound.direction != EmailDirection.OUTBOUND) {
        throw IllegalStateException("Cannot record sent metadata on an inbound message")
    }
    val outboundConversation = h.findConversationById(outbound.conversationId)!!
    h.setMessageInternetMessageIdAndCreatedAt(outbound.id, internetMessageId, emailCreatedAt)
    val updated = h.findMessageById(outbound.id)!!

    val waitingChildren = h.findWaitingChildren(internetMessageId)
    val convById = h.findConversationsByIds(waitingChildren.map { it.conversationId }.distinct()).associateBy { it.id }
    val participant = outboundConversation.participantAddress.lowercase()
    val eligibleChildren = waitingChildren.filter { convById[it.conversationId]?.participantAddress?.lowercase() == participant }
    val unassignedConversationIds = eligibleChildren
        .filter { it.conversationId != outbound.conversationId && convById[it.conversationId]?.topicType == null }
        .map { it.conversationId }
        .distinct()

    if (unassignedConversationIds.isNotEmpty()) {
        h.reassignMessagesToConversation(unassignedConversationIds, outbound.conversationId)
    }
    h.reparentSpecificChildren(eligibleChildren.map { it.id }, outbound.conversationId, outbound.id)
    if (unassignedConversationIds.isNotEmpty()) {
        h.deleteConversations(unassignedConversationIds)
    }
    h.maxEmailCreatedAt(outbound.conversationId)?.let { h.setConversationLastMessageAt(outbound.conversationId, it) }
    updated
}

fun Services.hydrateReferencedOutboundMessages(
    resend: ResendEmailClient,
    participantAddress: String,
    internetMessageIds: List<String>,
    preferredInternetMessageId: String?,
) {
    val ancestry = internetMessageIds.filter { it.isNotEmpty() }.distinct()
    if (ancestry.isEmpty()) return

    val known = jdbi.h { it.findFirstOutboundHydrationMessage(participantAddress, ancestry, preferredInternetMessageId) }
    if (known != null) return

    val candidates = jdbi.h {
        it.findOutboundHydrationCandidates(participantAddress, MAX_OUTBOUND_HYDRATION_CANDIDATES + 1)
    }
    if (candidates.isEmpty()) return

    val limited = candidates.take(MAX_OUTBOUND_HYDRATION_CANDIDATES)
    var matched = false
    var recentRetrievalFailed = false
    val retryCutoff = System.currentTimeMillis() - OUTBOUND_HYDRATION_RETRY_WINDOW_MS

    for (candidate in limited) {
        val retrieved = try {
            resend.getSent(candidate.resendEmailId!!)
        } catch (error: Throwable) {
            if (candidate.emailCreatedAt.toInstant().toEpochMilli() >= retryCutoff) recentRetrievalFailed = true
            continue
        }
        recordOutboundInternetMessageId(candidate.id, retrieved.messageId, parseInstant(retrieved.createdAt))
        if (ancestry.contains(retrieved.messageId)) matched = true
    }

    val overflowRecent = candidates.size > MAX_OUTBOUND_HYDRATION_CANDIDATES &&
        candidates[MAX_OUTBOUND_HYDRATION_CANDIDATES].emailCreatedAt.toInstant().toEpochMilli() >= retryCutoff
    if (!matched && (recentRetrievalFailed || overflowRecent)) {
        throw IllegalStateException("Outbound threading metadata could not be fully hydrated")
    }
}

fun Services.projectInboundEmail(
    eventData: EmailEventData,
    email: ResendEmail,
    replyToBaseAddress: String,
): EmailMessage {
    // An empty or malformed provider Message-ID is unusable for idempotency or threading;
    // treat it as absent so distinct emails are never conflated by a bad value.
    val candidateInternetMessageId = (eventData.messageId ?: email.messageId)
        .takeIf { isValidInternetMessageId(it) }
    val inReplyTo = extractMessageIds(getHeader(email.headers, "in-reply-to")).lastOrNull()
    val rawReferences = extractMessageIds(getHeader(email.headers, "references"))
        .filter { it.length <= 998 }
        .takeLast(100)
    val effectiveParent = inReplyTo ?: rawReferences.lastOrNull()
    val references = if (inReplyTo != null) rawReferences.filter { it != inReplyTo } + inReplyTo else rawReferences

    val displayFrom = parseAddress(getHeader(email.headers, "from") ?: email.from)
    val participantMailbox = parseAddress(eventData.from.ifEmpty { email.from })
    val emailCreatedAt = parseInstant(email.createdAt.ifEmpty { eventData.createdAt })
    val participantAddress = participantMailbox.address.lowercase()
    val routingTokens = extractRoutingTokens(
        email.to + eventData.to + (email.receivedFor ?: emptyList()) + (eventData.receivedFor ?: emptyList()),
        replyToBaseAddress,
    )

    for (attempt in 0 until 3) {
        try {
            return jdbi.tx(TransactionIsolationLevel.SERIALIZABLE) { h ->
                val lockIds = (listOfNotNull(candidateInternetMessageId) + references)
                    .filter { it.isNotEmpty() }.distinct().sorted()
                lockIds.forEach { h.advisoryXactLock(it) }

                // Idempotency is keyed on the Resend email id alone: a matching RFC Message-ID on a
                // different email id is a reuse (or forgery), not a redelivery.
                val existing = h.findMessageByResendEmailId(eventData.emailId)
                if (existing != null) return@tx existing

                // A distinct email reusing an already-stored Message-ID is projected as its own
                // message without one (the unique index permits multiple NULLs).
                val internetMessageId = candidateInternetMessageId
                    ?.takeIf { h.findMessageByInternetMessageId(it) == null }

                val ancestry = references.reversed()
                val related = if (ancestry.isNotEmpty()) h.findMessagesByInternetMessageIds(ancestry) else emptyList()
                val waitingRaw = internetMessageId?.let { h.findWaitingChildren(it) } ?: emptyList()
                val convIds = (related.map { it.conversationId } + waitingRaw.map { it.conversationId }).distinct()
                val convById = h.findConversationsByIds(convIds).associateBy { it.id }

                val eligibleMessages =
                    related.filter { convById[it.conversationId]?.participantAddress?.lowercase() == participantAddress }
                val waitingChildren =
                    waitingRaw.filter { convById[it.conversationId]?.participantAddress?.lowercase() == participantAddress }

                val parent = effectiveParent?.let { p -> eligibleMessages.firstOrNull { it.internetMessageId == p } }
                val nearestAncestor = ancestry.firstNotNullOfOrNull { mid ->
                    eligibleMessages.firstOrNull { it.internetMessageId == mid }
                }

                val parentConv = parent?.let { convById[it.conversationId] }
                val ancestorConv = nearestAncestor?.let { convById[it.conversationId] }
                val assignedWaiting = waitingChildren.firstOrNull { convById[it.conversationId]?.topicType != null }
                    ?.let { convById[it.conversationId] }
                val waitingConversation =
                    (waitingChildren.firstOrNull { convById[it.conversationId]?.topicType != null }
                        ?: waitingChildren.firstOrNull())?.let { convById[it.conversationId] }

                var conversation: EmailConversation? =
                    parentConv?.takeIf { it.topicType != null }
                        ?: ancestorConv?.takeIf { it.topicType != null }
                        ?: assignedWaiting
                        ?: parentConv
                        ?: ancestorConv
                        ?: waitingConversation

                if (conversation == null && routingTokens.isNotEmpty()) {
                    val routed = h.findConversationsByRoutingTokens(routingTokens, 2)
                    if (routed.size == 1 && routed[0].participantAddress.lowercase() == participantAddress) {
                        conversation = routed[0]
                    }
                }
                if (conversation == null) {
                    conversation = h.insertConversation(
                        NewConversation(
                            topicType = null,
                            externalTopicId = null,
                            title = normalizeSubject(email.subject),
                            subject = normalizeSubject(email.subject),
                            participantAddress = participantMailbox.address,
                            participantName = displayFrom.name,
                            lastMessageAt = emailCreatedAt,
                        ),
                    )
                }
                val chosen = conversation

                val foreignUnassigned = (eligibleMessages + waitingChildren)
                    .filter { it.conversationId != chosen.id && convById[it.conversationId]?.topicType == null }
                    .map { it.conversationId }
                    .distinct()
                if (foreignUnassigned.isNotEmpty()) {
                    h.reassignMessagesToConversation(foreignUnassigned, chosen.id)
                    h.deleteConversations(foreignUnassigned)
                }

                val message = h.insertMessage(
                    NewMessage(
                        conversationId = chosen.id,
                        parentMessageId = parent?.id,
                        direction = EmailDirection.INBOUND,
                        state = EmailMessageState.RECEIVED,
                        resendEmailId = eventData.emailId,
                        internetMessageId = internetMessageId,
                        inReplyToInternetMessageId = effectiveParent,
                        referenceInternetMessageIds = references,
                        fromAddress = participantMailbox.address,
                        fromName = displayFrom.name,
                        toAddress = email.to.firstOrNull() ?: eventData.to.firstOrNull() ?: "",
                        replyToAddress = email.replyTo?.firstOrNull(),
                        replyToName = null,
                        subject = email.subject,
                        textBody = email.text,
                        htmlBody = email.html,
                        emailCreatedAt = emailCreatedAt,
                    ),
                )

                internetMessageId?.let { h.reparentWaitingChildren(it, chosen.id, message.id) }
                h.maxEmailCreatedAt(chosen.id)?.let { h.setConversationLastMessageAt(chosen.id, it) }
                message
            }
        } catch (error: RuntimeException) {
            if (isSerializationFailure(error) && attempt < 2) continue
            if (isUniqueViolation(error)) {
                val existing = jdbi.h { it.findMessageByResendEmailId(eventData.emailId) }
                if (existing != null) return existing
            }
            throw error
        }
    }
    throw IllegalStateException("Inbound projection retry limit exhausted")
}
