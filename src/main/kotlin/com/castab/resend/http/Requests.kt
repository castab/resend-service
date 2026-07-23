package com.castab.resend.http

import kotlinx.serialization.json.JsonElement
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status

private const val BODY_LIMIT_BYTES = 2_100 * 1024

sealed interface BodyResult
data class BodyOk(val value: JsonElement) : BodyResult
data class BodyErr(val response: Response) : BodyResult

sealed interface RawBodyResult
data class RawBodyOk(val bytes: ByteArray) : RawBodyResult
data class RawBodyErr(val response: Response) : RawBodyResult

/** Reads the request body, enforcing the shared size limit: oversize -> 413. */
fun readBoundedBody(request: Request): RawBodyResult {
    val bytes = request.body.payload.let { buf ->
        ByteArray(buf.remaining()).also { buf.duplicate().get(it) }
    }
    if (bytes.size > BODY_LIMIT_BYTES) {
        return RawBodyErr(error(Status(413, "Payload Too Large"), "Request body is too large"))
    }
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
