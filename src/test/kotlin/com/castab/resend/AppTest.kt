package com.castab.resend

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.http4k.core.*

class AppTest : StringSpec({
    val config = Config(3000, "localhost", null, null, null, null, null, "conversation-secret", "drain-secret")
    val app = application(config)
    "health remains public and aggregate" {
        val response = app(Request(Method.GET, "/api/health/v1"))
        response.status shouldBe Status.SERVICE_UNAVAILABLE
        response.bodyString() shouldBe "{\"status\":\"unavailable\"}"
    }
    "conversation endpoints require bearer authentication" {
        app(Request(Method.GET, "/api/conversations/v1")).status shouldBe Status.UNAUTHORIZED
    }
    "drain endpoint requires its dedicated credential" {
        app(Request(Method.POST, "/api/conversations/v1/outbox/drain").header("Authorization", "Bearer conversation-secret")).status shouldBe Status.UNAUTHORIZED
    }
})
