package com.iboism.gpxrecorder.recording

import com.iboism.gpxrecorder.recording.location.RecordingLocation
import kotlin.math.max

internal object RecordingTrackPointPolicy {
    private const val MIN_SEGMENT_GAP_MILLIS = 5 * 60 * 1000L

    fun shouldRecord(location: RecordingLocation): Boolean {
        if (location.lat !in -90.0..90.0 || location.lon !in -180.0..180.0) return false
        if (location.lat == 0.0 && location.lon == 0.0) return false

        return true
    }

    fun shouldStartNewSegment(elapsedSinceLastPointMillis: Long, recordingIntervalMillis: Long): Boolean {
        val gapThreshold = max(recordingIntervalMillis * 2, MIN_SEGMENT_GAP_MILLIS)
        return elapsedSinceLastPointMillis >= gapThreshold
    }
}
