package com.dialex.util

/**
 * Returns a simple date string in YYYYMMDD format without external datetime dependencies.
 */
fun nowDateString(): String {
    val totalSeconds = (System.currentTimeMillis() / 1000).coerceAtLeast(0)
    val daysSinceEpoch = totalSeconds / 86400

    // Approximate civil year/month/day from days since Jan 1 1970
    var z = daysSinceEpoch + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = (z - era * 146097)
    val yoe = (doe - doe / 1024 + doe / 1461 - doe / 142401) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = mp + (if (mp < 10) 3 else -9)
    val actualYear = y + (if (m <= 2) 1 else 0)

    val mm = if (m < 10) "0$m" else "$m"
    val dd = if (d < 10) "0$d" else "$d"
    return "$actualYear-$mm-$dd"
}

/**
 * Formats a timestamp into a clean relative time string:
 * - < 1 min: "now"
 * - < 60 min: "${n}m"
 * - < 24 hours: "${n}h"
 * - >= 24 hours: "${n}d"
 */
fun formatRelativeTime(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (timestampMs <= 0L) return "now"
    val diffMs = (nowMs - timestampMs).coerceAtLeast(0L)
    val diffMinutes = diffMs / (60 * 1000L)
    val diffHours = diffMs / (60 * 60 * 1000L)
    val diffDays = diffMs / (24 * 60 * 60 * 1000L)

    return when {
        diffMinutes < 1 -> "now"
        diffMinutes < 60 -> "${diffMinutes}m"
        diffHours < 24 -> "${diffHours}h"
        diffDays == 1L -> "1d"
        diffDays < 30 -> "${diffDays}d"
        else -> "${diffDays / 30}mo"
    }
}

/**
 * Formats a message timestamp:
 * - If today (same day): shows time only, e.g. "3:45 PM"
 * - If different day: shows date & time, e.g. "Sep 2, 3:45 PM"
 */
fun formatMessageTimestamp(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val ts = if (timestampMs <= 0L) nowMs else timestampMs
    val totalSeconds = (ts / 1000).coerceAtLeast(0)
    val daysSinceEpoch = totalSeconds / 86400
    val nowDaysSinceEpoch = (nowMs / 1000).coerceAtLeast(0) / 86400

    // Time calculation (UTC/local epoch approximation)
    val secondsOfDay = totalSeconds % 86400
    val hours24 = (secondsOfDay / 3600).toInt()
    val minutes = ((secondsOfDay % 3600) / 60).toInt()
    val ampm = if (hours24 >= 12) "PM" else "AM"
    val hours12 = if (hours24 % 12 == 0) 12 else hours24 % 12
    val mm = if (minutes < 10) "0$minutes" else "$minutes"
    val timeStr = "$hours12:$mm $ampm"

    if (daysSinceEpoch == nowDaysSinceEpoch) {
        return timeStr
    }

    // Date calculation
    var z = daysSinceEpoch + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = (z - era * 146097)
    val yoe = (doe - doe / 1024 + doe / 1461 - doe / 142401) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = mp + (if (mp < 10) 3 else -9)

    val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val monthName = monthNames.getOrElse((m - 1).toInt()) { "$m" }

    return "$monthName $d, $timeStr"
}

/**
 * Formats a timestamp into a full date and time string (e.g. "Sep 21, 5:35 PM").
 */
fun formatDateTime(timestampMs: Long): String {
    if (timestampMs <= 0L) return ""
    val totalSeconds = (timestampMs / 1000).coerceAtLeast(0)
    val daysSinceEpoch = totalSeconds / 86400

    val secondsOfDay = totalSeconds % 86400
    val hours24 = (secondsOfDay / 3600).toInt()
    val minutes = ((secondsOfDay % 3600) / 60).toInt()
    val ampm = if (hours24 >= 12) "PM" else "AM"
    val hours12 = if (hours24 % 12 == 0) 12 else hours24 % 12
    val mm = if (minutes < 10) "0$minutes" else "$minutes"
    val timeStr = "$hours12:$mm $ampm"

    var z = daysSinceEpoch + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = (z - era * 146097)
    val yoe = (doe - doe / 1024 + doe / 1461 - doe / 142401) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = mp + (if (mp < 10) 3 else -9)

    val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val monthName = monthNames.getOrElse((m - 1).toInt()) { "$m" }

    return "$monthName $d, $timeStr"
}


/**
 * Formats a token count into compact human-readable format:
 * - < 1,000: e.g. "450"
 * - < 1,000,000: e.g. "108K", "12.5K"
 * - >= 1,000,000: e.g. "53M", "1.2M"
 */
fun formatTokenCount(tokens: Long): String {
    if (tokens <= 0L) return "0"
    if (tokens < 1_000L) return "$tokens"
    if (tokens < 1_000_000L) {
        val k = tokens / 1_000.0
        val rounded = kotlin.math.round(k * 10) / 10.0
        return if (rounded % 1.0 == 0.0) "${rounded.toLong()}K" else "${rounded}K"
    }
    val m = tokens / 1_000_000.0
    val rounded = kotlin.math.round(m * 10) / 10.0
    return if (rounded % 1.0 == 0.0) "${rounded.toLong()}M" else "${rounded}M"
}

fun formatTokenCount(tokens: Int): String = formatTokenCount(tokens.toLong())
