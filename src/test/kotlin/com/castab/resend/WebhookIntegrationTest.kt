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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status

private const val CREATE_BODY =
    """{"topic":{"type":"order","externalId":"o-9","title":"Order 1"},"participant":{"email":"buyer@example.com","name":"Buyer"},"message":{"text":"Hello"}}"""

/** A minimal `email.received` webhook payload; the full email is fetched from the fake by id. */
private fun receivedPayload(emailId: String, from: String = "External Person <external@example.com>") =
    """{"type":"email.received","created_at":"2026-07-19T03:52:03.099Z","data":{"email_id":"$emailId","from":"$from","to":["inbox@example.com"],"subject":"Received Email","created_at":"2026-07-19T03:52:03.099Z"}}"""

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

    "object-shaped tags on a delivered event are stored and projected" {
        val created = app(conversationRequest(Method.POST, "/api/conversations/v1", CREATE_BODY, "wh-tags-1"))
        val conversationId = bodyJson(created)["conversationId"]!!.jsonPrimitive.content
        val resendEmailId = fake.sends.first().id

        // Resend sends tags as a flat key-value object, not an array of {name, value}.
        val payload = """{"type":"email.delivered","created_at":"2026-07-01T00:00:00.000Z","data":{"email_id":"$resendEmailId","from":"support@mail.example.test","to":["buyer@example.com"],"subject":"Order 1","created_at":"2026-07-01T00:00:00.000Z","tags":{"category":"confirm_email"}}}"""
        val response = app(webhookRequest(Svix.sign(TestFactory.WEBHOOK_SECRET, payload)))
        response.status shouldBe Status.OK

        val fetched = app(conversationRequest(Method.GET, "/api/conversations/v1/$conversationId"))
        val message = bodyJson(fetched)["messages"]!!.jsonArray[0].jsonObject
        message["deliveryState"]!!.jsonPrimitive.content shouldBe "delivered"
    }

    "object-shaped tags on a received event are stored and projected" {
        val payload = """{"type":"email.received","created_at":"2026-07-19T03:52:03.099Z","data":{"email_id":"em_received123","from":"External Person <external@example.com>","to":["inbox@example.com"],"subject":"Received Email","created_at":"2026-07-19T03:52:03.099Z","tags":{"category":"inbound"}}}"""
        val response = app(webhookRequest(Svix.sign(TestFactory.WEBHOOK_SECRET, payload)))
        response.status shouldBe Status.OK

        val list = app(conversationRequest(Method.GET, "/api/conversations/v1?assignment=unassigned"))
        bodyJson(list)["conversations"]!!.jsonArray.size shouldBe 1
    }

    "distinct emails sharing a valid RFC Message-ID are not conflated" {
        fake.addReceived("em_dup1", "<dup@example.com>")
        fake.addReceived("em_dup2", "<dup@example.com>")
        app(webhookRequest(Svix.sign(TestFactory.WEBHOOK_SECRET, receivedPayload("em_dup1")))).status shouldBe Status.OK
        app(webhookRequest(Svix.sign(TestFactory.WEBHOOK_SECRET, receivedPayload("em_dup2")))).status shouldBe Status.OK

        val list = app(conversationRequest(Method.GET, "/api/conversations/v1?assignment=unassigned"))
        val conversations = bodyJson(list)["conversations"]!!.jsonArray
        conversations.size shouldBe 2

        // The first projection keeps the Message-ID; the reused one is stored without it.
        val internetMessageIds = conversations.map { conversation ->
            val id = conversation.jsonObject["id"]!!.jsonPrimitive.content
            val fetched = app(conversationRequest(Method.GET, "/api/conversations/v1/$id"))
            bodyJson(fetched)["messages"]!!.jsonArray[0].jsonObject["internetMessageId"]!!.jsonPrimitive.contentOrNull
        }
        internetMessageIds.toSet() shouldBe setOf("<dup@example.com>", null)
    }

    "empty and malformed RFC Message-IDs do not conflate distinct emails" {
        fake.addReceived("em_noid", "")
        fake.addReceived("em_badid", "not-a-message-id")
        app(webhookRequest(Svix.sign(TestFactory.WEBHOOK_SECRET, receivedPayload("em_noid")))).status shouldBe Status.OK
        app(webhookRequest(Svix.sign(TestFactory.WEBHOOK_SECRET, receivedPayload("em_badid")))).status shouldBe Status.OK

        val list = app(conversationRequest(Method.GET, "/api/conversations/v1?assignment=unassigned"))
        val conversations = bodyJson(list)["conversations"]!!.jsonArray
        conversations.size shouldBe 2
        conversations.forEach { conversation ->
            val id = conversation.jsonObject["id"]!!.jsonPrimitive.content
            val fetched = app(conversationRequest(Method.GET, "/api/conversations/v1/$id"))
            bodyJson(fetched)["messages"]!!.jsonArray[0].jsonObject["internetMessageId"]!!.jsonPrimitive.contentOrNull shouldBe null
        }
    }

    "a replayed completed inbound delivery is acknowledged despite a provider failure" {
        fake.addReceived("em_ghost", "<ghost-inbound@example.com>", from = "external@example.com", references = "<ghost-parent@example.com>")
        val signed = Svix.sign(TestFactory.WEBHOOK_SECRET, receivedPayload("em_ghost", "external@example.com"), "msg_replay_1")
        app(webhookRequest(signed)).status shouldBe Status.OK

        // An outbound message to the same participant that cannot hydrate its Message-ID.
        fake.failSentRetrievals = true
        val outboundBody =
            """{"topic":{"type":"order","externalId":"o-replay","title":"Order R"},"participant":{"email":"external@example.com","name":"External"},"message":{"text":"Hello"}}"""
        app(conversationRequest(Method.POST, "/api/conversations/v1", outboundBody, "wh-replay")).status shouldBe Status.CREATED

        // The projection already exists, so the replay must still acknowledge with 200.
        app(webhookRequest(signed)).status shouldBe Status.OK
    }

    "an oversized webhook body is rejected before decoding" {
        val request = Request(Method.POST, "/api/webhooks/resend/v1")
            .header("svix-id", "msg_big")
            .header("svix-timestamp", (System.currentTimeMillis() / 1000).toString())
            .header("svix-signature", "v1,irrelevant")
            .body("a".repeat(2_100 * 1024 + 1))
        app(request).status.code shouldBe 413
    }
    }
})
