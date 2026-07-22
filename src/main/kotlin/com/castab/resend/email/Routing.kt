package com.castab.resend.email

import java.util.UUID

private const val ROUTING_TAG_PREFIX = "c_"
private const val ROUTING_TOKEN_LENGTH = 32
private val TOKEN_PATTERN = Regex("^[0-9a-f]{32}$")
private val LOCAL_PATTERN = Regex("^[a-z0-9.!#\$%&'*/=?^_`{|}~-]+$")
private val LABEL_PATTERN = Regex("^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$")

private data class MailboxParts(val local: String, val domain: String)

private fun parseBaseMailbox(address: String): MailboxParts? {
    val normalized = address.trim().lowercase()
    val at = normalized.lastIndexOf('@')
    if (at <= 0 ||
        at == normalized.length - 1 ||
        normalized.contains('<') ||
        normalized.contains('>') ||
        normalized.substring(0, at).contains('+')
    ) {
        return null
    }

    val local = normalized.substring(0, at)
    val domain = normalized.substring(at + 1)
    val generatedLocalLength = local.length + 1 + ROUTING_TAG_PREFIX.length + ROUTING_TOKEN_LENGTH
    val domainLabels = domain.split(".")
    if (!LOCAL_PATTERN.matches(local) ||
        local.startsWith(".") ||
        local.endsWith(".") ||
        local.contains("..") ||
        generatedLocalLength > 64 ||
        domain.length > 253 ||
        domainLabels.size < 2 ||
        domainLabels.any { it.isEmpty() || it.length > 63 || !LABEL_PATTERN.matches(it) } ||
        generatedLocalLength + domain.length + 1 > 254
    ) {
        return null
    }
    return MailboxParts(local, domain)
}

fun isValidReplyToBaseAddress(address: String): Boolean = parseBaseMailbox(address) != null

fun createRoutingToken(): String = UUID.randomUUID().toString()

fun buildConversationReplyTo(baseAddress: String, routingToken: String): String {
    val base = parseBaseMailbox(baseAddress)
    val token = routingToken.replace("-", "").lowercase()
    if (base == null || !TOKEN_PATTERN.matches(token)) {
        throw IllegalStateException("Invalid conversation Reply-To configuration")
    }
    return "${base.local}+$ROUTING_TAG_PREFIX$token@${base.domain}"
}

fun extractRoutingTokens(addresses: List<String>, baseAddress: String): List<String> {
    val base = parseBaseMailbox(baseAddress)
        ?: throw IllegalStateException("Invalid conversation Reply-To configuration")
    val prefix = "${base.local}+$ROUTING_TAG_PREFIX"
    val tokens = LinkedHashSet<String>()
    for (rawAddress in addresses) {
        val address = rawAddress.trim().lowercase()
        val at = address.lastIndexOf('@')
        if (at <= 0 || address.substring(at + 1) != base.domain) continue
        val local = address.substring(0, at)
        if (!local.startsWith(prefix)) continue
        val token = local.substring(prefix.length)
        if (TOKEN_PATTERN.matches(token)) {
            tokens.add(
                "${token.substring(0, 8)}-${token.substring(8, 12)}-${token.substring(12, 16)}-" +
                    "${token.substring(16, 20)}-${token.substring(20)}",
            )
        }
    }
    return tokens.toList()
}
