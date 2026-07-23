package com.castab.resend.http

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.http4k.core.Method
import org.http4k.core.Request
import java.io.InputStream

class RequestsTest : StringSpec({
    "an oversized declared content-length is rejected without accessing the stream" {
        var accessed = false
        val sentinel = object : InputStream() {
            override fun read(): Int {
                accessed = true
                return -1
            }
        }
        val request = Request(Method.POST, "/api/webhooks/resend/v1")
            .header("content-length", (100L * 1024 * 1024).toString())
            .body(sentinel, null)

        val result = readBoundedBody(request)

        result.shouldBeInstanceOf<RawBodyErr>().response.status.code shouldBe 413
        accessed shouldBe false
    }

    "an unbounded stream is abandoned at the limit instead of drained" {
        var produced = 0L
        val endless = object : InputStream() {
            override fun read(): Int {
                produced++
                return 'a'.code
            }
        }
        val request = Request(Method.POST, "/api/webhooks/resend/v1").body(endless, null)

        val result = readBoundedBody(request)

        result.shouldBeInstanceOf<RawBodyErr>().response.status.code shouldBe 413
        // At most one chunk past the 2,100 KiB limit is ever read.
        produced shouldBeLessThan (2_100L * 1024 + 64 * 1024 + 1)
    }

    "a body within the limit is read completely" {
        val request = Request(Method.POST, "/api/webhooks/resend/v1").body("""{"type":"email.sent"}""")

        val result = readBoundedBody(request)

        String(result.shouldBeInstanceOf<RawBodyOk>().bytes, Charsets.UTF_8) shouldBe """{"type":"email.sent"}"""
    }
})
