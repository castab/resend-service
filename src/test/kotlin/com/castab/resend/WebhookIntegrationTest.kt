package com.castab.resend

import com.castab.resend.support.FakeResendServer
import com.castab.resend.support.Svix
import com.castab.resend.support.TestDb
import com.castab.resend.support.TestFactory
import com.castab.resend.support.TestFactory.bodyJson
import com.castab.resend.support.TestFactory.conversationRequest
import com.castab.resend.support.TestFactory.webhookRequest
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
    """{"topic":{"type":"order","externalId":"o-9","title":"Order 1"},"participant":{"email":"buyer@example.com","name":"Buyer"},"message":{"text":"Hello"}}"""

class WebhookIntegrationTest : StringSpec({
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

    "missing svix headers are rejected" {
        val response = app(Request(Method.POST, "/api/webhooks/resend/v1").body("""{"type":"email.sent"}"""))
        response.status shouldBe Status.BAD_REQUEST
    }

    "an invalid signature is rejected" {
        val request = Request(Method.POST, "/api/webhooks/resend/v1")
            .header("svix-id", "msg_1")
            .header("svix-timestamp", (System.currentTimeMillis() / 1000).toString())
            .header("svix-signature", "v1,not-a-real-signature")
            .body("""{"type":"email.sent"}""")
        app(request).status shouldBe Status.UNAUTHORIZED
    }

    "a delivered event projects delivery state onto the outbound message" {
        val created = app(conversationRequest(Method.POST, "/api/conversations/v1", CREATE_BODY, "wh-1"))
        val conversationId = bodyJson(created)["conversationId"]!!.jsonPrimitive.content
        val resendEmailId = fake.sends.first().id

        val payload = """{"type":"email.delivered","created_at":"2026-07-01T00:00:00.000Z","data":{"email_id":"$resendEmailId","from":"support@mail.example.test","to":["buyer@example.com"],"subject":"Order 1","created_at":"2026-07-01T00:00:00.000Z"}}"""
        val response = app(webhookRequest(Svix.sign(TestFactory.WEBHOOK_SECRET, payload)))
        response.status shouldBe Status.OK

        val fetched = app(conversationRequest(Method.GET, "/api/conversations/v1/$conversationId"))
        val message = bodyJson(fetched)["messages"]!!.jsonArray[0].jsonObject
        message["deliveryState"]!!.jsonPrimitive.content shouldBe "delivered"
    }

    "a received event projects an inbound message into a new conversation" {
        val payload = """{"type":"email.received","created_at":"2026-07-19T03:52:03.099Z","data":{"email_id":"em_received123","from":"External Person <external@example.com>","to":["inbox@example.com"],"subject":"Received Email","created_at":"2026-07-19T03:52:03.099Z"}}"""
        val response = app(webhookRequest(Svix.sign(TestFactory.WEBHOOK_SECRET, payload)))
        response.status shouldBe Status.OK

        val list = app(conversationRequest(Method.GET, "/api/conversations/v1?assignment=unassigned"))
        bodyJson(list)["conversations"]!!.jsonArray.size shouldBe 1
    }

    "duplicate deliveries are acknowledged idempotently" {
        val created = app(conversationRequest(Method.POST, "/api/conversations/v1", CREATE_BODY, "wh-2"))
        val resendEmailId = fake.sends.first().id
        val payload = """{"type":"email.bounced","created_at":"2026-07-01T00:00:00.000Z","data":{"email_id":"$resendEmailId","from":"support@mail.example.test","to":["buyer@example.com"],"subject":"Order 1","created_at":"2026-07-01T00:00:00.000Z","bounce":{"type":"Permanent","subType":"General","message":"mailbox full","diagnosticCode":[]}}}"""
        val signed = Svix.sign(TestFactory.WEBHOOK_SECRET, payload, "msg_dupe")
        app(webhookRequest(signed)).status shouldBe Status.OK
        app(webhookRequest(signed)).status shouldBe Status.OK
    }
    }
})
