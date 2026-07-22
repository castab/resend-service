package com.castab.resend

import java.security.MessageDigest

internal fun authorized(header: String?, expected: String?): Boolean {
    if (expected.isNullOrEmpty()) return false
    val supplied = Regex("^Bearer +([^ ]+)$", RegexOption.IGNORE_CASE).matchEntire(header.orEmpty())?.groupValues?.get(1)
        ?: return false
    return MessageDigest.isEqual(expected.toByteArray(), supplied.toByteArray())
}

