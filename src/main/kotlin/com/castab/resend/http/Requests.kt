package com.castab.resend.http

import kotlinx.serialization.json.JsonElement
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import java.io.ByteArrayOutputStream
import java.io.InputStream

private const val BODY_LIMIT_BYTES = 2_100 * 1024

sealed interface BodyResult
data class BodyOk(val value: JsonElement) : BodyResult
data class BodyErr(val response: Response) : BodyResult

sealed interface RawBodyResult
data class RawBodyOk(val bytes: ByteArray) : RawBodyResult
data class RawBodyErr(val response: Response) : RawBodyResult

private fun payloadTooLarge(): RawBodyErr =
    RawBodyErr(error(Status(413, "Payload Too Large"), "Request body is too large"))

/**
 * Reads [stream] completely up to [limitBytes], in chunks; returns null as soon as the limit is
 * crossed so an oversized payload is never fully materialized in memory.
 */
fun readBoundedStream(stream: InputStream, limitBytes: Int, sizeHint: Int = 8_192): ByteArray? {
    val collected = ByteArrayOutputStream(minOf(sizeHint, limitBytes).coerceAtLeast(16))
    val chunk = ByteArray(64 * 1024)
    var total = 0
    while (true) {
        val read = stream.read(chunk)
        if (read < 0) break
        total += read
        if (total > limitBytes) return null
        collected.write(chunk, 0, read)
    }
    return collected.toByteArray()
}

/**
 * Reads the request body, enforcing the shared size limit: oversize -> 413. An oversized declared
 * length is rejected before the stream is touched.
 */
fun readBoundedBody(request: Request): RawBodyResult {
    val declaredLength = request.header("content-length")?.toLongOrNull()
    if (declaredLength != null && declaredLength > BODY_LIMIT_BYTES) return payloadTooLarge()

    val sizeHint = minOf(declaredLength ?: 8_192L, BODY_LIMIT_BYTES.toLong()).coerceAtLeast(16L).toInt()
    val bytes = readBoundedStream(request.body.stream, BODY_LIMIT_BYTES, sizeHint) ?: return payloadTooLarge()
    return RawBodyOk(bytes)
}

/**
 * Reads and parses the JSON request body, reproducing the Express body-parser contract:
 * compressed bodies -> 415, oversize -> 413, malformed JSON -> 400.
 */
fun readJsonBody(request: Request): BodyResult {
    if (!request.header("content-encoding").isNullOrBlank()) {
        return BodyErr(error(Status(415, "Unsupported Media Type"), "Compressed request bodies are not supported"))
    }
    val bytes = when (val raw = readBoundedBody(request)) {
        is RawBodyErr -> return BodyErr(raw.response)
        is RawBodyOk -> raw.bytes
    }
    return try {
        BodyOk(json.parseToJsonElement(String(bytes, Charsets.UTF_8)))
    } catch (e: Exception) {
        BodyErr(error(Status.BAD_REQUEST, "Request body must be valid JSON"))
    }
}

/** Returns the Idempotency-Key when present and <= 256 chars, else the 400 response to return. */
fun requireIdempotencyKey(request: Request): Pair<String?, Response?> {
    val key = request.header("idempotency-key")
    return if (key.isNullOrEmpty() || key.length > 256) {
        null to error(Status.BAD_REQUEST, "A valid Idempotency-Key header is required")
    } else {
        key to null
    }
}

/** `?limit` clamped to [1,100]; non-numeric or absent -> 50 (matches `getPageLimit`). */
fun pageLimit(request: Request): Int {
    val value = request.query("limit") ?: return 50
    val parsed = value.toIntOrNull() ?: return 50
    return parsed.coerceIn(1, 100)
}
