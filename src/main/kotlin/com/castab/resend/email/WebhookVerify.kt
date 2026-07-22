package com.castab.resend.email

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

sealed interface VerifyResult
data class VerifyOk(val body: String) : VerifyResult
data class VerifyErr(val error: String) : VerifyResult

/**
 * Verifies a Svix-signed webhook (the scheme Resend uses) against the exact raw body:
 * `base64(HMAC_SHA256(secret, "${id}.${timestamp}.${body}"))` compared, constant-time, against each
 * `v1,<sig>` entry in the space-separated signature header, with a 5-minute timestamp tolerance.
 */
object WebhookVerifier {
    private const val TOLERANCE_SECONDS = 5 * 60L

    fun verify(
        rawBody: String,
        svixId: String,
        svixTimestamp: String,
        svixSignature: String,
        secret: String,
    ): VerifyResult {
        val timestamp = svixTimestamp.toLongOrNull()
            ?: return VerifyErr("Invalid Signature Headers")
        val now = System.currentTimeMillis() / 1000
        if (abs(now - timestamp) > TOLERANCE_SECONDS) {
            return VerifyErr("Message timestamp outside of tolerance")
        }

        val keyBytes = try {
            Base64.getDecoder().decode(secret.removePrefix("whsec_"))
        } catch (e: IllegalArgumentException) {
            return VerifyErr("Invalid secret")
        }

        val signedContent = "$svixId.$svixTimestamp.$rawBody"
        val expected = try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
            Base64.getEncoder().encodeToString(mac.doFinal(signedContent.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            return VerifyErr("Signature computation failed")
        }

        val expectedBytes = expected.toByteArray(Charsets.UTF_8)
        val matched = svixSignature.split(" ").any { entry ->
            if (!entry.startsWith("v1,")) return@any false
            val signature = entry.substring(3)
            MessageDigest.isEqual(expectedBytes, signature.toByteArray(Charsets.UTF_8))
        }
        return if (matched) VerifyOk(rawBody) else VerifyErr("No matching signature found")
    }
}
