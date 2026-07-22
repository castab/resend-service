package com.castab.resend.email

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ThreadingTest : StringSpec({
    "extracts ordered RFC message IDs and ignores malformed values" {
        extractMessageIds("noise <one@example.test> broken <two@example.test>") shouldBe
            listOf("<one@example.test>", "<two@example.test>")
    }
    "normalizes repeated reply prefixes" { normalizeSubject(" Re: Fwd: Hello ") shouldBe "Hello" }
    "references preserve ancestry order without duplicates" {
        buildReferences(listOf("<a@test>", "<b@test>"), "<b@test>") shouldBe listOf("<a@test>", "<b@test>")
    }
    "parses named and bare mailboxes" {
        parseAddress("\"Ada Lovelace\" <ada@example.test>") shouldBe Mailbox("ada@example.test", "Ada Lovelace")
        parseAddress("ada@example.test") shouldBe Mailbox("ada@example.test", null)
    }
})

