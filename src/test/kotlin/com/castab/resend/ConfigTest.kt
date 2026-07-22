package com.castab.resend

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ConfigTest : StringSpec({
    "loads a type-safe HOCON resource" {
        Config.load("/test-application.conf") shouldBe Config(
            port = 8088,
            host = "127.0.0.1",
            databaseUrl = "postgresql://localhost/test",
            resendApiKey = "test-api-key",
            webhookSecret = "test-webhook-secret",
            resendFrom = "Mailbox <mailbox@example.test>",
            resendReplyTo = "mailbox@replies.example.test",
            conversationApiKey = "conversation-secret",
            outboxDrainApiKey = "drain-secret",
        )
    }
})
