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
