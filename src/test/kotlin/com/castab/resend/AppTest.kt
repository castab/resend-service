package com.castab.resend

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status

class AppTest : StringSpec({
    val config = Config(
        port = 3000,
        host = "localhost",
        conversationApiKey = "conversation-secret",
        outboxDrainApiKey = "drain-secret",
    )
    val app = application(config)

    "health remains public and aggregate" {
        val response = app(Request(Method.GET, "/api/health/v1"))
        response.status shouldBe Status.SERVICE_UNAVAILABLE
        response.bodyString() shouldBe "{\"status\":\"unhealthy\"}"
    }
    "health rejects query parameters" {
        app(Request(Method.GET, "/api/health/v1").query("foo", "bar")).status shouldBe Status.BAD_REQUEST
    }
    "conversation endpoints require bearer authentication" {
        app(Request(Method.GET, "/api/conversations/v1")).status shouldBe Status.UNAUTHORIZED
    }
    "drain endpoint requires its dedicated credential" {
        app(
            Request(Method.POST, "/api/conversations/v1/outbox/drain")
                .header("Authorization", "Bearer conversation-secret"),
        ).status shouldBe Status.UNAUTHORIZED
    }
})
