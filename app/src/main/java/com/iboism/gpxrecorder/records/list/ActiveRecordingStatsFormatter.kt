package com.iboism.gpxrecorder.records.list

import java.util.Locale
import java.util.concurrent.TimeUnit

object ActiveRecordingStatsFormatter {
    fun formatElapsedMillis(elapsedMillis: Long): String {
        val safeElapsedMillis = elapsedMillis.coerceAtLeast(0L)
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(safeElapsedMillis)
        val hours = totalSeconds / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }
}
