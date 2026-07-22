package com.castab.resend.email

private val messageId = Regex("<[^<>\\s]+>")
private val subjectPrefix = Regex("^\\s*(?:re|fw|fwd)\\s*:\\s*", RegexOption.IGNORE_CASE)

fun extractMessageIds(value: String?): List<String> = value?.let { messageId.findAll(it).map(MatchResult::value).toList() }.orEmpty()

fun normalizeSubject(value: String): String {
    var normalized = value.trim()
    while (subjectPrefix.containsMatchIn(normalized)) normalized = subjectPrefix.replaceFirst(normalized, "")
    return normalized.ifBlank { value.trim() }
}

fun replySubject(value: String) = "Re: ${normalizeSubject(value)}"
fun createReplySubject(value: String) = replySubject(value)
fun buildReferences(ancestry: List<String>, parent: String) = (ancestry + parent).distinct()

/** Case-insensitive header lookup over a raw header map (mirrors the Express `getHeader`). */
fun getHeader(headers: Map<String, String>?, name: String): String? {
    if (headers == null) return null
    val target = name.lowercase()
    return headers.entries.firstOrNull { it.key.lowercase() == target }?.value
}

data class Mailbox(val address: String, val name: String?)
fun parseAddress(value: String): Mailbox {
    val match = Regex("^(.*?)\\s*<([^<>]+)>$").matchEntire(value.trim())
        ?: return Mailbox(value.trim(), null)
    return Mailbox(match.groupValues[2].trim(), match.groupValues[1].trim().trim('"').ifBlank { null })
}

