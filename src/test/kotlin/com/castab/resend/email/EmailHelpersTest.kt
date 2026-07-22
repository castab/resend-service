package com.castab.resend.email

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import com.castab.resend.support.Svix

class EmailHelpersTest : StringSpec({
    "validates reply-to base addresses" {
        isValidReplyToBaseAddress("support@mail.example.test") shouldBe true
        isValidReplyToBaseAddress("support+tag@mail.example.test") shouldBe false
        isValidReplyToBaseAddress("no-at-symbol") shouldBe false
    }
    "builds and extracts conversation routing tokens" {
        val token = "0190bd3e-1c4a-7abc-8def-000000000abc"
        val address = buildConversationReplyTo("support@mail.example.test", token)
        address shouldBe "support+c_${token.replace("-", "")}@mail.example.test"
        extractRoutingTokens(listOf(address), "support@mail.example.test") shouldBe listOf(token)
    }
    "delivery projection keeps the strongest non-delayed state" {
        val base = java.time.OffsetDateTime.parse("2026-07-01T00:00:00Z")
        val events = listOf(
            StoredDeliveryEvent("1", "email.delivery_delayed", base, base, "slow"),
            StoredDeliveryEvent("2", "email.delivered", base.plusMinutes(1), base.plusMinutes(1), null),
            StoredDeliveryEvent("3", "email.delivery_delayed", base.plusMinutes(2), base.plusMinutes(2), "later"),
        )
        val projection = reduceDeliveryEvents(events)
        projection.current!!.state.name shouldBe "DELIVERED"
        projection.deliveredAt shouldBe base.plusMinutes(1)
    }
    "svix verification accepts a correct signature and rejects tampering" {
        val secret = "whsec_" + java.util.Base64.getEncoder().encodeToString("secret-material".toByteArray())
        val signed = Svix.sign(secret, """{"type":"email.sent"}""")
        WebhookVerifier.verify(
            signed.body,
            signed.headers.getValue("svix-id"),
            signed.headers.getValue("svix-timestamp"),
            signed.headers.getValue("svix-signature"),
            secret,
        ) shouldBe VerifyOk(signed.body)

        val tampered = WebhookVerifier.verify(
            """{"type":"email.bounced"}""",
            signed.headers.getValue("svix-id"),
            signed.headers.getValue("svix-timestamp"),
            signed.headers.getValue("svix-signature"),
            secret,
        )
        (tampered is VerifyErr) shouldBe true
    }
    "formats reply-to display names, quoting when necessary" {
        com.castab.resend.service.formatReplyToAddress("a@b.co", null) shouldBe "a@b.co"
        com.castab.resend.service.formatReplyToAddress("a@b.co", "Ada Lovelace") shouldBe "Ada Lovelace <a@b.co>"
        com.castab.resend.service.formatReplyToAddress("a@b.co", "Ada, Lovelace") shouldStartWith "\""
    }
})
