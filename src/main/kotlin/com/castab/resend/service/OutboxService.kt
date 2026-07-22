package com.castab.resend.service

import com.castab.resend.data.acceptBatchMessages
import com.castab.resend.data.assignEntriesToBatch
import com.castab.resend.data.createOutboxBatch
import com.castab.resend.data.deleteOrphanOutboxEntries
import com.castab.resend.data.deleteOwnedBatch
import com.castab.resend.data.findBatchMessagesOrdered
import com.castab.resend.data.findOutboxBatch
import com.castab.resend.data.h
import com.castab.resend.data.isBatchLeaseOwned
import com.castab.resend.data.markBatchMessagesState
import com.castab.resend.data.reclaimBatch
import com.castab.resend.data.scheduleBatchRetry
import com.castab.resend.data.selectRetryableBatchId
import com.castab.resend.data.selectUnbatchedMessageIds
import com.castab.resend.data.tx
import com.castab.resend.domain.EmailMessage
import com.castab.resend.domain.EmailMessageState
import com.castab.resend.email.ResendApiError
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

private const val OUTBOX_LEASE_MS = 2L * 60 * 1000
private const val PROVIDER_IDEMPOTENCY_SAFETY_MS = 23L * 60 * 60 * 1000
private val RETRY_DELAYS_MS = longArrayOf(60_000, 120_000, 300_000)

private data class ClaimedBatch(
    val id: String,
    val leaseToken: String,
    val attemptCount: Int,
    val firstAttemptAt: OffsetDateTime,
    val messages: List<EmailMessage>,
)

fun Services.drainEmailOutbox(limit: Int): JsonObject {
    val claimed = claimOutboxBatch(limit) ?: return emptyDrainResult()
    if (claimed.attemptCount > 1 && hasProviderWindowExpired(claimed)) {
        return finalizeIndeterminateBatch(claimed, "Outbox batch exceeded the provider idempotency window")
    }

    return try {
        val ids = configuredResend().sendBatch(
            claimed.messages.map(::buildSendEmailInput),
            "conversation-outbox/${claimed.id}",
        )
        if (ids.size != claimed.messages.size || ids.any { it.isNullOrEmpty() }) {
            scheduleBatchRetryResult(claimed, "invalid_batch_response")
        } else {
            finalizeAcceptedBatch(claimed, ids.map { it!! })
        }
    } catch (error: Throwable) {
        if (error is ResendApiError && error.status == 409 && error.code == "invalid_idempotent_request") {
            finalizeIndeterminateBatch(claimed, "Resend rejected a changed payload for the persisted batch key")
        } else if (isRetryableBatchError(error)) {
            scheduleBatchRetryResult(claimed, errorCode(error))
        } else {
            finalizeFailedBatch(claimed, errorCode(error))
        }
    }
}

private fun Services.claimOutboxBatch(limit: Int): ClaimedBatch? {
    val leaseToken = UUID.randomUUID().toString()
    val moment = OffsetDateTime.now(ZoneOffset.UTC)
    val leaseUntil = moment.plusNanos(OUTBOX_LEASE_MS * 1_000_000)

    return jdbi.tx { h ->
        h.deleteOrphanOutboxEntries()
        val retryable = h.selectRetryableBatchId(moment)
        val id: String
        if (retryable == null) {
            val entries = h.selectUnbatchedMessageIds(limit)
            if (entries.isEmpty()) return@tx null
            id = h.createOutboxBatch(leaseToken, leaseUntil, moment)
            h.assignEntriesToBatch(id, entries)
        } else {
            id = retryable
            h.reclaimBatch(id, leaseToken, leaseUntil)
        }
        val batch = h.findOutboxBatch(id)!!
        val messages = h.findBatchMessagesOrdered(id)
        if (batch.firstAttemptAt == null || messages.any { it.state != EmailMessageState.PENDING }) {
            throw IllegalStateException("Claimed outbox batch contains invalid message state")
        }
        ClaimedBatch(id, leaseToken, batch.attemptCount, batch.firstAttemptAt, messages)
    }
}

private fun Services.finalizeAcceptedBatch(batch: ClaimedBatch, resendEmailIds: List<String>): JsonObject {
    jdbi.tx { h ->
        assertLeaseOwner(h, batch)
        val updated = h.acceptBatchMessages(batch.messages.map { it.id }, resendEmailIds)
        if (updated != batch.messages.size) throw IllegalStateException("Outbox message changed before batch completion")
        resendEmailIds.forEach { projectOutboundDeliveryState(h, it) }
        if (h.deleteOwnedBatch(batch.id, batch.leaseToken) != 1) {
            throw IllegalStateException("Outbox batch lease was lost before cleanup")
        }
    }
    return drainResult(
        batchId = batch.id,
        claimed = batch.messages.size,
        accepted = batch.messages.size,
        results = batch.messages.mapIndexed { index, message ->
            DrainEntry(message.id, "accepted", resendEmailIds[index])
        },
    )
}

private fun Services.finalizeFailedBatch(batch: ClaimedBatch, errorCode: String): JsonObject {
    jdbi.tx { h ->
        assertLeaseOwner(h, batch)
        h.markBatchMessagesState(batch.messages.map { it.id }, EmailMessageState.FAILED, "Resend batch request failed ($errorCode)")
        if (h.deleteOwnedBatch(batch.id, batch.leaseToken) != 1) {
            throw IllegalStateException("Outbox batch lease was lost before cleanup")
        }
    }
    return terminalDrainResult(batch, "failed")
}

private fun Services.finalizeIndeterminateBatch(batch: ClaimedBatch, detail: String): JsonObject {
    jdbi.tx { h ->
        assertLeaseOwner(h, batch)
        h.markBatchMessagesState(batch.messages.map { it.id }, EmailMessageState.INDETERMINATE, detail)
        if (h.deleteOwnedBatch(batch.id, batch.leaseToken) != 1) {
            throw IllegalStateException("Outbox batch lease was lost before cleanup")
        }
    }
    return terminalDrainResult(batch, "indeterminate")
}

private fun Services.scheduleBatchRetryResult(batch: ClaimedBatch, errorCode: String): JsonObject {
    if (hasProviderWindowExpired(batch)) {
        return finalizeIndeterminateBatch(batch, "Outbox batch exceeded the provider idempotency window")
    }
    val delay = RETRY_DELAYS_MS[minOf(batch.attemptCount - 1, RETRY_DELAYS_MS.size - 1)]
    val nextAttemptAt = OffsetDateTime.now(ZoneOffset.UTC).plusNanos(delay * 1_000_000)
    val updated = jdbi.h { it.scheduleBatchRetry(batch.id, batch.leaseToken, nextAttemptAt, errorCode) }
    if (updated != 1) throw IllegalStateException("Outbox batch lease was lost before retry scheduling")
    return drainResult(
        batchId = batch.id,
        claimed = batch.messages.size,
        retryScheduled = batch.messages.size,
        results = batch.messages.map { DrainEntry(it.id, "pending", null) },
    )
}

private fun assertLeaseOwner(h: org.jdbi.v3.core.Handle, batch: ClaimedBatch) {
    if (!h.isBatchLeaseOwned(batch.id, batch.leaseToken)) {
        throw IllegalStateException("Outbox batch lease was lost before completion")
    }
}

private fun isRetryableBatchError(error: Throwable): Boolean {
    if (error !is ResendApiError) return true
    return (error.status == 429 && error.code != "monthly_quota_exceeded" && error.code != "daily_quota_exceeded") ||
        error.status >= 500 ||
        (error.status == 409 && error.code == "concurrent_idempotent_requests")
}

private fun hasProviderWindowExpired(batch: ClaimedBatch): Boolean =
    System.currentTimeMillis() - batch.firstAttemptAt.toInstant().toEpochMilli() >= PROVIDER_IDEMPOTENCY_SAFETY_MS

private fun errorCode(error: Throwable): String = when (error) {
    is ResendApiError -> error.code ?: "http_${error.status}"
    else -> error::class.simpleName ?: "unknown_error"
}

// --- result shaping ---

private data class DrainEntry(val messageId: String, val state: String, val resendEmailId: String?)

private fun drainResult(
    batchId: String?,
    claimed: Int = 0,
    accepted: Int = 0,
    failed: Int = 0,
    retryScheduled: Int = 0,
    indeterminate: Int = 0,
    results: List<DrainEntry> = emptyList(),
): JsonObject = buildJsonObject {
    put("batchId", batchId?.let { JsonPrimitive(it) } ?: JsonNull)
    put("claimed", JsonPrimitive(claimed))
    put("accepted", JsonPrimitive(accepted))
    put("failed", JsonPrimitive(failed))
    put("retryScheduled", JsonPrimitive(retryScheduled))
    put("indeterminate", JsonPrimitive(indeterminate))
    put("results", buildJsonArray {
        results.forEach { entry ->
            add(buildJsonObject {
                put("messageId", JsonPrimitive(entry.messageId))
                put("state", JsonPrimitive(entry.state))
                put("resendEmailId", entry.resendEmailId?.let { JsonPrimitive(it) } ?: JsonNull)
            })
        }
    })
}

private fun terminalDrainResult(batch: ClaimedBatch, state: String): JsonObject = drainResult(
    batchId = batch.id,
    claimed = batch.messages.size,
    failed = if (state == "failed") batch.messages.size else 0,
    indeterminate = if (state == "indeterminate") batch.messages.size else 0,
    results = batch.messages.map { DrainEntry(it.id, state, null) },
)

private fun emptyDrainResult(): JsonObject = drainResult(batchId = null)
