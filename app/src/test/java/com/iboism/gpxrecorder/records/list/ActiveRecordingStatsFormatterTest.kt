package com.iboism.gpxrecorder.records.list

import org.junit.Assert.assertEquals
import org.junit.Test

class ActiveRecordingStatsFormatterTest {
    @Test
    fun formatsElapsedSecondsAsClockText() {
        assertEquals("00:00:00", ActiveRecordingStatsFormatter.formatElapsedMillis(0))
        assertEquals("00:00:05", ActiveRecordingStatsFormatter.formatElapsedMillis(5_000))
        assertEquals("00:01:05", ActiveRecordingStatsFormatter.formatElapsedMillis(65_000))
        assertEquals("01:01:05", ActiveRecordingStatsFormatter.formatElapsedMillis(3_665_000))
    }

    @Test
    fun clampsNegativeElapsedTimeToZero() {
        assertEquals("00:00:00", ActiveRecordingStatsFormatter.formatElapsedMillis(-1_000))
    }
}
