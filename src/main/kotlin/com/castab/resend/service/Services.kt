package com.castab.resend.service

import com.castab.resend.Config
import com.castab.resend.email.ResendEmailClient
import org.jdbi.v3.core.Jdbi

/** Shared dependencies for the business services (config + database). */
class Services(val config: Config, val jdbi: Jdbi) {
    /** Mirrors `getConfiguredResendClient`: throws with the exact "Missing RESEND_API_KEY" prefix when unset. */
    fun configuredResend(): ResendEmailClient {
        val key = config.resendApiKey
        if (key.isNullOrBlank()) throw IllegalStateException("Missing RESEND_API_KEY environment variable")
        return ResendEmailClient(key, config.resendApiBaseUrl?.takeIf { it.isNotBlank() } ?: "https://api.resend.com")
    }
}
