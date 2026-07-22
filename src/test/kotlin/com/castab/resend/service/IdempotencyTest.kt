package com.castab.resend.service

import com.castab.resend.validation.CreateConversationInput
import com.castab.resend.validation.MessageBodyInput
import com.castab.resend.validation.ParticipantInput
import com.castab.resend.validation.TopicInput
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class IdempotencyTest : StringSpec({
    val create = CreateConversationInput(
        topic = TopicInput("order", "o-1", "Order 1"),
        participant = ParticipantInput("buyer@example.com", "Buyer"),
        subject = null,
        message = MessageContentOf("Hello", null, null),
    )
    val reply = MessageBodyInput(text = "Reply", html = null, replyToMessageId = null, replyToName = null)

    "hashing is deterministic" {
        hashCreate(create) shouldBe hashCreate(create)
        hashReply("c-1", reply) shouldBe hashReply("c-1", reply)
    }
    "each operation namespaces its hash" {
        hashCreate(create) shouldNotBe hashOutboxOpen(create)
        hashReply("c-1", reply) shouldNotBe hashOutboxReply("c-1", reply)
        hashReply("c-1", reply) shouldNotBe hashReply("c-2", reply)
    }
    "canonical create body follows the documented key order" {
        createCanonical(create).toString() shouldBe
            """{"topic":{"type":"order","externalId":"o-1","title":"Order 1"},"participant":{"email":"buyer@example.com","name":"Buyer"},"message":{"text":"Hello"}}"""
    }
})

private fun MessageContentOf(text: String?, html: String?, replyToName: String?) =
    com.castab.resend.validation.MessageContent(text, html, replyToName)
