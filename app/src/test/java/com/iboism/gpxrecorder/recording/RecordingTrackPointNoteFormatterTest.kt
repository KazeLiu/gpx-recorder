package com.iboism.gpxrecorder.recording

import com.iboism.gpxrecorder.recording.location.LocationMethod
import com.iboism.gpxrecorder.recording.location.LocationProviderName
import com.iboism.gpxrecorder.recording.location.LocationSignalStrength
import com.iboism.gpxrecorder.recording.location.RecordingLocation
import com.iboism.gpxrecorder.recording.location.RecordingLocationStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date

class RecordingTrackPointNoteFormatterTest {
    @Test
    fun writesRoundedAccuracyToNote() {
        val location = recordingLocation(accuracyMeters = 650.4f)

        assertEquals("定位精度：约 650 米", RecordingTrackPointNoteFormatter.accuracyNote(location))
    }

    @Test
    fun writesUnknownWhenAccuracyIsMissing() {
        val location = recordingLocation(accuracyMeters = null)

        assertEquals("定位精度：未知", RecordingTrackPointNoteFormatter.accuracyNote(location))
    }

    private fun recordingLocation(accuracyMeters: Float?): RecordingLocation {
        return RecordingLocation(
            lat = 31.2304,
            lon = 121.4737,
            ele = null,
            time = Date(0L),
            status = RecordingLocationStatus(
                provider = LocationProviderName.Amap,
                method = LocationMethod.Cell,
                accuracyMeters = accuracyMeters,
                signalStrength = LocationSignalStrength.Weak
            )
        )
    }
}
