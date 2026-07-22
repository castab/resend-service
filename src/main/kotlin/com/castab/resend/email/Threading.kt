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
fun buildReferences(ancestry: List<String>, parent: String) = (ancestry + parent).distinct()

data class Mailbox(val address: String, val name: String?)
fun parseAddress(value: String): Mailbox {
    val match = Regex("^(.*?)\\s*<([^<>]+)>$").matchEntire(value.trim())
        ?: return Mailbox(value.trim(), null)
    return Mailbox(match.groupValues[2].trim(), match.groupValues[1].trim().trim('"').ifBlank { null })
}

