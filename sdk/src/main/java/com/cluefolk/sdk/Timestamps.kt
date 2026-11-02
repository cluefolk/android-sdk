package com.cluefolk.sdk

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal object Timestamps {
    fun parseRfc3339(value: String): Long {
        // Avoid java.time to preserve API 24 without consumer desugaring.
        val normalized = value.replace(Regex("\\.\\d+(?=Z|[+-]\\d{2}:\\d{2}$)"), "")
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        format.isLenient = false
        val position = ParsePosition(0)
        val result =
            format.parse(normalized, position) ?: throw IllegalArgumentException("Invalid expiry")
        require(position.index == normalized.length) { "Invalid expiry" }
        return result.time
    }
}
