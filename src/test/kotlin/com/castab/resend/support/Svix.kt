package com.castab.resend.support

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class SignedWebhook(val body: String, val headers: Map<String, String>)

/** Signs a payload the way the Svix client does, for driving the webhook route in tests. */
object Svix {
    fun sign(secret: String, body: String, msgId: String = generateSvixId()): SignedWebhook {
        val timestamp = System.currentTimeMillis() / 1000
        val keyBytes = Base64.getDecoder().decode(secret.removePrefix("whsec_"))
        val signedContent = "$msgId.$timestamp.$body"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
        val signature = Base64.getEncoder().encodeToString(mac.doFinal(signedContent.toByteArray(Charsets.UTF_8)))
        return SignedWebhook(
            body = body,
            headers = mapOf(
                "svix-id" to msgId,
                "svix-timestamp" to timestamp.toString(),
                "svix-signature" to "v1,$signature",
                "content-type" to "application/json",
            ),
        )
    }

    private fun generateSvixId(): String = "msg_" + java.util.UUID.randomUUID().toString().replace("-", "")
}
