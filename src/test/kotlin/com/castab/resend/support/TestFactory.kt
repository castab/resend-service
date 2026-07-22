package com.castab.resend.support

import com.castab.resend.Config
import com.castab.resend.application
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.http4k.core.Body
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import java.util.Base64

object TestFactory {
    const val CONVERSATION_KEY = "conversation-key"
    const val DRAIN_KEY = "drain-key"
    val WEBHOOK_SECRET = "whsec_" + Base64.getEncoder().encodeToString("integration-secret-material".toByteArray())
    const val RESEND_FROM = "Support <support@mail.example.test>"
    const val RESEND_REPLY_TO = "support@mail.example.test"

    private val codec = Json { ignoreUnknownKeys = true }

    fun config(fake: FakeResendServer): Config = Config(
        port = 3000,
        host = "localhost",
        databaseUrl = TestDb.url,
        resendApiKey = "test-resend-key",
        resendApiBaseUrl = fake.baseUrl,
        webhookSecret = WEBHOOK_SECRET,
        resendFrom = RESEND_FROM,
        resendReplyTo = RESEND_REPLY_TO,
        conversationApiKey = CONVERSATION_KEY,
        outboxDrainApiKey = DRAIN_KEY,
    )

    fun app(fake: FakeResendServer): HttpHandler = application(config(fake), TestDb.source)

    fun conversationRequest(method: Method, path: String, body: String? = null, idempotencyKey: String? = null): Request {
        var request = Request(method, path).header("Authorization", "Bearer $CONVERSATION_KEY")
        if (idempotencyKey != null) request = request.header("Idempotency-Key", idempotencyKey)
        if (body != null) request = request.header("content-type", "application/json").body(body)
        return request
    }

    fun drainRequest(body: String): Request =
        Request(Method.POST, "/api/conversations/v1/outbox/drain")
            .header("Authorization", "Bearer $DRAIN_KEY")
            .header("content-type", "application/json")
            .body(body)

    fun webhookRequest(signed: SignedWebhook): Request {
        var request = Request(Method.POST, "/api/webhooks/resend/v1").body(signed.body)
        signed.headers.forEach { (name, value) -> request = request.header(name, value) }
        return request
    }

    fun bodyJson(response: Response): JsonObject = codec.parseToJsonElement(response.bodyString()) as JsonObject
    fun element(response: Response): JsonElement = codec.parseToJsonElement(response.bodyString())
}
