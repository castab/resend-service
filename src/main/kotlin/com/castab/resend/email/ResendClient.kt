package com.castab.resend.email

import com.castab.resend.http.json
import com.castab.resend.http.readBoundedStream
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import org.http4k.client.OkHttp
import org.http4k.core.BodyMode
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import java.net.URLEncoder
import java.time.Duration

/** Resend outbound payload; nullable fields are omitted from the wire object, matching the Express client. */
data class SendEmailInput(
    val from: String,
    val to: List<String>,
    val replyTo: String?,
    val subject: String,
    val text: String?,
    val html: String?,
    val headers: Map<String, String>?,
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("from", JsonPrimitive(from))
        put("to", JsonArray(to.map { JsonPrimitive(it) }))
        if (replyTo != null) put("reply_to", JsonPrimitive(replyTo))
        put("subject", JsonPrimitive(subject))
        if (text != null) put("text", JsonPrimitive(text))
        if (html != null) put("html", JsonPrimitive(html))
        if (headers != null) {
            put("headers", buildJsonObject { headers.forEach { (k, v) -> put(k, JsonPrimitive(v)) } })
        }
    }
}

@Serializable
data class ResendEmail(
    val id: String,
    @SerialName("message_id") val messageId: String,
    val from: String = "",
    val to: List<String> = emptyList(),
    val subject: String = "",
    @SerialName("created_at") val createdAt: String,
    val text: String? = null,
    val html: String? = null,
    val headers: Map<String, String>? = null,
    @SerialName("reply_to") val replyTo: List<String>? = null,
    @SerialName("received_for") val receivedFor: List<String>? = null,
)

/** Non-2xx response from Resend; carries the HTTP status and the parsed `name`/`code` for retry classification. */
class ResendApiError(
    message: String,
    val status: Int,
    val responseBody: String,
    val code: String? = null,
) : RuntimeException(message)

/** Transport/timeout failure reaching Resend (not an HTTP error response). */
class ResendTransportError(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Provider responses that are safe to retry with the same idempotency key: the request was not
 * accepted (rate limit) or its outcome is unknown (server error, concurrent identical request).
 */
fun isRetryableResendApiError(error: ResendApiError): Boolean =
    (error.status == 429 && error.code != "monthly_quota_exceeded" && error.code != "daily_quota_exceeded") ||
        error.status >= 500 ||
        (error.status == 409 && error.code == "concurrent_idempotent_requests")

/** Resend returned a response larger than the applicable read bound. */
class ResendResponseTooLargeError(message: String) : RuntimeException(message)

class ResendEmailClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.resend.com",
    private val http: HttpHandler = defaultClient,
) {
    companion object {
        /** Streaming body mode so oversized provider responses can be abandoned mid-read. */
        private val defaultClient: HttpHandler =
            OkHttp(OkHttpClient.Builder().callTimeout(Duration.ofSeconds(15)).build(), BodyMode.Stream)

        /** Send/batch/error payloads are small JSON documents. */
        private const val API_RESPONSE_LIMIT_BYTES = 1 * 1024 * 1024

        /** Retrieved sent/received emails carry full text and HTML bodies. */
        private const val EMAIL_RESPONSE_LIMIT_BYTES = 10 * 1024 * 1024
    }

    private fun request(request: Request, responseLimitBytes: Int = API_RESPONSE_LIMIT_BYTES): String {
        val withHeaders = request
            .header("authorization", "Bearer $apiKey")
            .header("content-type", "application/json")
            .header("user-agent", "resend-service/2.0")
        val response = try {
            http(withHeaders)
        } catch (e: Exception) {
            throw ResendTransportError("Resend request failed: ${e.message}", e)
        }
        if (!response.status.successful) {
            val body = readBounded(response, API_RESPONSE_LIMIT_BYTES)
            throw ResendApiError(
                "Resend API request failed with status ${response.status.code}",
                response.status.code,
                body,
                extractCode(body),
            )
        }
        return readBounded(response, responseLimitBytes)
    }

    private fun readBounded(response: Response, limitBytes: Int): String {
        val bytes = response.body.stream.use { readBoundedStream(it, limitBytes) }
            ?: throw ResendResponseTooLargeError("Resend response exceeded the $limitBytes-byte limit")
        return String(bytes, Charsets.UTF_8)
    }

    private fun extractCode(body: String): String? = runCatching {
        val obj = json.parseToJsonElement(body) as? JsonObject ?: return null
        (obj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: (obj["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }.getOrNull()

    fun send(input: SendEmailInput, idempotencyKey: String): String {
        val body = request(
            Request(Method.POST, "$baseUrl/emails")
                .header("idempotency-key", idempotencyKey)
                .body(input.toJsonObject().toString()),
        )
        val obj = json.parseToJsonElement(body) as JsonObject
        return (obj["id"] as JsonPrimitive).content
    }

    /** Returns the ordered list of accepted email ids (may contain nulls for malformed responses). */
    fun sendBatch(inputs: List<SendEmailInput>, idempotencyKey: String): List<String?> {
        val payload: JsonElement = buildJsonArray { inputs.forEach { add(it.toJsonObject()) } }
        val body = request(
            Request(Method.POST, "$baseUrl/emails/batch")
                .header("idempotency-key", idempotencyKey)
                .body(payload.toString()),
        )
        val data = (json.parseToJsonElement(body) as JsonObject)["data"] as? JsonArray ?: JsonArray(emptyList())
        return data.map { item ->
            ((item as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content
        }
    }

    fun getSent(id: String): ResendEmail = json.decodeFromString(
        ResendEmail.serializer(),
        request(Request(Method.GET, "$baseUrl/emails/${enc(id)}"), EMAIL_RESPONSE_LIMIT_BYTES),
    )

    fun getReceived(id: String): ResendEmail = json.decodeFromString(
        ResendEmail.serializer(),
        request(Request(Method.GET, "$baseUrl/emails/receiving/${enc(id)}?html_format=cid"), EMAIL_RESPONSE_LIMIT_BYTES),
    )

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
}
