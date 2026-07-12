package com.madmandriver.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Fmt {
    private fun formatter(pattern: String, zone: ZoneId): DateTimeFormatter =
        DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).withZone(zone)

    fun dateTime(epochMs: Long, zone: ZoneId): String =
        formatter("d MMM yyyy HH:mm:ss", zone).format(Instant.ofEpochMilli(epochMs))

    fun date(epochMs: Long, zone: ZoneId): String =
        formatter("d.M.yyyy", zone).format(Instant.ofEpochMilli(epochMs))

    fun time(epochMs: Long, zone: ZoneId): String =
        formatter("HH:mm:ss", zone).format(Instant.ofEpochMilli(epochMs))

    fun fileStamp(epochMs: Long, zone: ZoneId): String =
        formatter("yyyy-MM-dd_HH-mm", zone).format(Instant.ofEpochMilli(epochMs))

    fun elapsed(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }
}
