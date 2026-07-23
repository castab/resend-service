package com.castab.resend.service

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.OffsetDateTime
import java.time.ZoneOffset

class ParseInstantTest : StringSpec({
    "parses strict ISO instants" {
        parseInstant("2026-07-19T03:52:03.099Z") shouldBe
            OffsetDateTime.of(2026, 7, 19, 3, 52, 3, 99_000_000, ZoneOffset.UTC)
    }

    "parses Resend's Postgres-style retrieve timestamp" {
        parseInstant("2026-04-03 22:13:42.674981+00") shouldBe
            OffsetDateTime.of(2026, 4, 3, 22, 13, 42, 674_981_000, ZoneOffset.UTC)
    }

    "parses offset timestamps without fractional seconds" {
        parseInstant("2026-04-03 22:13:42+00") shouldBe
            OffsetDateTime.of(2026, 4, 3, 22, 13, 42, 0, ZoneOffset.UTC)
    }

    "normalizes non-UTC offsets to UTC" {
        parseInstant("2026-04-03 22:13:42.500000+02:00") shouldBe
            OffsetDateTime.of(2026, 4, 3, 20, 13, 42, 500_000_000, ZoneOffset.UTC)
    }

    "rejects garbage values" {
        shouldThrowAny { parseInstant("not-a-timestamp") }
    }
})
