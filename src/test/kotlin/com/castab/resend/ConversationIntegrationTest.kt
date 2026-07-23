package com.castab.resend

import com.castab.resend.support.FakeResendServer
import com.castab.resend.support.TestDb
import com.castab.resend.support.TestFactory
import com.castab.resend.support.TestFactory.bodyJson
import com.castab.resend.support.TestFactory.conversationRequest
import com.castab.resend.support.TestFactory.drainRequest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status

private const val CREATE_BODY =
    """{"topic":{"type":"order","externalId":"o-1","title":"Order 1"},"participant":{"email":"buyer@example.com","name":"Buyer"},"message":{"text":"Hello"}}"""

class ConversationIntegrationTest : StringSpec({
    if (TestDb.enabled) {
    val fake = FakeResendServer()
    lateinit var app: HttpHandler
    beforeSpec {
        fake.start()
        app = TestFactory.app(fake)
    }
    afterSpec { fake.stop() }
    beforeTest {
        fake.reset()
        TestDb.truncate()
    }

    "create sends the opening email synchronously and returns 201 accepted" {
        val response = app(conversationRequest(Method.POST, "/api/conversations/v1", CREATE_BODY, "idem-1"))
        response.status shouldBe Status.CREATED
        val message = bodyJson(response)["message"]!!.jsonObject
        message["direction"]!!.jsonPrimitive.content shouldBe "outbound"
        message["state"]!!.jsonPrimitive.content shouldBe "accepted"
        fake.sends.size shouldBe 1
    }

    "replaying the same idempotency key and body returns the stored result" {
        app(conversationRequest(Method.POST, "/api/conversations/v1", CREATE_BODY, "idem-2"))
        val replay = app(conversationRequest(Method.POST, "/api/conversations/v1", CREATE_BODY, "idem-2"))
        replay.status shouldBe Status.OK
        fake.sends.size shouldBe 1
    }

    "reusing a key with a different body conflicts" {
        app(conversationRequest(Method.POST, "/api/conversations/v1", CREATE_BODY, "idem-3"))
        val other = CREATE_BODY.replace("Hello", "Different")
        app(conversationRequest(Method.POST, "/api/conversations/v1", other, "idem-3")).status shouldBe Status.CONFLICT
    }

    "invalid bodies are rejected with 400" {
        app(conversationRequest(Method.POST, "/api/conversations/v1", """{}""", "idem-4")).status shouldBe Status.BAD_REQUEST
    }

    "missing idempotency key is rejected" {
        app(conversationRequest(Method.POST, "/api/conversations/v1", CREATE_BODY)).status shouldBe Status.BAD_REQUEST
    }

    "conversation can be fetched and a reply threads correctly" {
        val created = app(conversationRequest(Method.POST, "/api/conversations/v1", CREATE_BODY, "idem-5"))
        val conversationId = bodyJson(created)["conversationId"]!!.jsonPrimitive.content

        val fetched = app(conversationRequest(Method.GET, "/api/conversations/v1/$conversationId"))
        fetched.status shouldBe Status.OK
        bodyJson(fetched)["messages"]!!.jsonArray.size shouldBe 1

        val reply = app(
            conversationRequest(
                Method.POST,
                "/api/conversations/v1/$conversationId/messages",
                """{"text":"Following up"}""",
                "idem-reply-1",
            ),
        )
        reply.status shouldBe Status.CREATED
        bodyJson(reply)["message"]!!.jsonObject["subject"]!!.jsonPrimitive.content shouldBe "Re: Order 1"
    }

    "an explicit reply parent from another conversation is rejected" {
        val createdA = app(conversationRequest(Method.POST, "/api/conversations/v1", CREATE_BODY, "idem-parent-a"))
        val otherBody = CREATE_BODY.replace("o-1", "o-parent-b")
        val createdB = app(conversationRequest(Method.POST, "/api/conversations/v1", otherBody, "idem-parent-b"))
        val parentFromA = bodyJson(createdA)["message"]!!.jsonObject["id"]!!.jsonPrimitive.content
        val conversationB = bodyJson(createdB)["conversationId"]!!.jsonPrimitive.content

        val reply = app(
            conversationRequest(
                Method.POST,
                "/api/conversations/v1/$conversationB/messages",
                """{"text":"Cross-conversation reply","replyToMessageId":"$parentFromA"}""",
                "idem-parent-reply",
            ),
        )
        reply.status shouldBe Status.NOT_FOUND

        // Nothing was sent or persisted for the rejected reply.
        fake.sends.size shouldBe 2
        val fetched = app(conversationRequest(Method.GET, "/api/conversations/v1/$conversationB"))
        bodyJson(fetched)["messages"]!!.jsonArray.size shouldBe 1
    }

    "an ambiguous provider conflict keeps the message pending and a replay retries the same key" {
        fake.failNextSendStatus = 409
        fake.failNextSendCode = "concurrent_idempotent_requests"
        val first = app(conversationRequest(Method.POST, "/api/conversations/v1", CREATE_BODY, "idem-amb-409"))
        first.status shouldBe Status.BAD_GATEWAY
        bodyJson(first)["message"]!!.jsonObject["state"]!!.jsonPrimitive.content shouldBe "pending"
        fake.sends.size shouldBe 0

        // Replaying the original API idempotency key retries with the same provider key.
        val replay = app(conversationRequest(Method.POST, "/api/conversations/v1", CREATE_BODY, "idem-amb-409"))
        replay.status shouldBe Status.OK
        bodyJson(replay)["message"]!!.jsonObject["state"]!!.jsonPrimitive.content shouldBe "accepted"
        fake.sends.size shouldBe 1
        fake.sends.first().idempotencyKey shouldBe
            "conversation/${bodyJson(replay)["message"]!!.jsonObject["id"]!!.jsonPrimitive.content}"
    }

    "rate limits and server errors do not become terminal failures" {
        listOf(429 to "rate_limit_exceeded", 500 to "internal_server_error").forEachIndexed { index, (status, code) ->
            fake.failNextSendStatus = status
            fake.failNextSendCode = code
            val body = CREATE_BODY.replace("o-1", "o-amb-$index")
            val response = app(conversationRequest(Method.POST, "/api/conversations/v1", body, "idem-amb-$index"))
            response.status shouldBe Status.BAD_GATEWAY
            bodyJson(response)["message"]!!.jsonObject["state"]!!.jsonPrimitive.content shouldBe "pending"
        }
        fake.sends.size shouldBe 0
    }

    "a topic with an ambiguous opening send cannot be reopened with a new key" {
        fake.failNextSendStatus = 409
        fake.failNextSendCode = "concurrent_idempotent_requests"
        app(conversationRequest(Method.POST, "/api/conversations/v1", CREATE_BODY, "idem-amb-reopen-1"))
            .status shouldBe Status.BAD_GATEWAY

        // The first provider request may still succeed, so a fresh provider key must be refused.
        val reopen = app(conversationRequest(Method.POST, "/api/conversations/v1", CREATE_BODY, "idem-amb-reopen-2"))
        reopen.status shouldBe Status.CONFLICT
        fake.sends.size shouldBe 0
    }

    "a terminal provider rejection stays failed and allows reopening the topic" {
        fake.failNextSendStatus = 422
        fake.failNextSendCode = "validation_error"
        val first = app(conversationRequest(Method.POST, "/api/conversations/v1", CREATE_BODY, "idem-term-1"))
        first.status shouldBe Status.BAD_GATEWAY
        bodyJson(first)["message"]!!.jsonObject["state"]!!.jsonPrimitive.content shouldBe "failed"

        val reopened = app(conversationRequest(Method.POST, "/api/conversations/v1", CREATE_BODY, "idem-term-2"))
        reopened.status shouldBe Status.CREATED
        fake.sends.size shouldBe 1
    }

    "an oversized provider send response leaves the message indeterminate" {
        fake.oversizeNextSendResponse = true
        val response = app(conversationRequest(Method.POST, "/api/conversations/v1", CREATE_BODY, "idem-oversize-1"))
        response.status shouldBe Status.BAD_GATEWAY
        bodyJson(response)["message"]!!.jsonObject["state"]!!.jsonPrimitive.content shouldBe "indeterminate"
    }

    "topic lookup and assignment conflict behave correctly" {
        app(conversationRequest(Method.POST, "/api/conversations/v1", CREATE_BODY, "idem-6"))
        val byTopic = app(conversationRequest(Method.GET, "/api/conversations/v1/topics/order/o-1"))
        byTopic.status shouldBe Status.OK

        val conversationId = bodyJson(byTopic)["id"]!!.jsonPrimitive.content
        val reassign = app(
            conversationRequest(
                Method.PATCH,
                "/api/conversations/v1/$conversationId",
                """{"topic":{"type":"order","externalId":"o-2","title":"Order 1"}}""",
            ),
        )
        reassign.status shouldBe Status.CONFLICT
    }

    "outbox enqueue then drain accepts the batch" {
        val enqueue = app(
            conversationRequest(Method.POST, "/api/conversations/v1/outbox", CREATE_BODY, "idem-7"),
        )
        enqueue.status shouldBe Status.ACCEPTED
        bodyJson(enqueue)["message"]!!.jsonObject["state"]!!.jsonPrimitive.content shouldBe "pending"

        val drain = app(drainRequest("""{"limit":10}"""))
        drain.status shouldBe Status.OK
        val result = bodyJson(drain)
        result["accepted"]!!.jsonPrimitive.content shouldBe "1"
        result["results"]!!.jsonArray[0].jsonObject["state"]!!.jsonPrimitive.content shouldBe "accepted"
        fake.batches.size shouldBe 1
    }

    "list only supports assignment=unassigned" {
        app(Request(Method.GET, "/api/conversations/v1").header("Authorization", "Bearer ${TestFactory.CONVERSATION_KEY}"))
            .status shouldBe Status.BAD_REQUEST
        app(conversationRequest(Method.GET, "/api/conversations/v1?assignment=unassigned")).status shouldBe Status.OK
    }
    }
})
