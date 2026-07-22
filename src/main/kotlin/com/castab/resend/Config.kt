package com.castab.resend

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.addResourceSource

data class Config(
    val port: Int = 3000,
    val host: String = "0.0.0.0",
    val databaseUrl: String? = null,
    val resendApiKey: String? = null,
    val webhookSecret: String? = null,
    val resendFrom: String? = null,
    val resendReplyTo: String? = null,
    val conversationApiKey: String? = null,
    val outboxDrainApiKey: String? = null,
) {
    val configured: Boolean get() = listOf(databaseUrl, resendApiKey, webhookSecret, resendFrom,
        resendReplyTo, conversationApiKey, outboxDrainApiKey).all { !it.isNullOrBlank() }

    companion object {
        fun load(resource: String = "/application.conf"): Config = ConfigLoaderBuilder.default()
            .addResourceSource(resource)
            .build()
            .loadConfigOrThrow()
    }
}
