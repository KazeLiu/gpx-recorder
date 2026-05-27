package com.iboism.gpxrecorder.recording

import com.iboism.gpxrecorder.model.RecordingConfiguration
import com.iboism.gpxrecorder.recording.location.LocationMethod
import com.iboism.gpxrecorder.recording.location.LocationProviderName
import com.iboism.gpxrecorder.recording.location.LocationSignalStrength
import com.iboism.gpxrecorder.recording.location.RecordingLocation
import com.iboism.gpxrecorder.recording.location.RecordingLocationStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class RecordingTrackPointPolicyTest {
    @Test
    fun allowsAmapCellLocationsWithApproximateAccuracy() {
        val location = recordingLocation(
            method = LocationMethod.Cell,
            accuracyMeters = 650f
        )

        assertTrue(RecordingTrackPointPolicy.shouldRecord(location))
    }

    @Test
    fun allowsGpsLocationsWithPoorAccuracy() {
        val location = recordingLocation(
            method = LocationMethod.Gps,
            accuracyMeters = 650f
        )

        assertTrue(RecordingTrackPointPolicy.shouldRecord(location))
    }

    @Test
    fun rejectsInvalidZeroCoordinate() {
        val location = recordingLocation(
            lat = 0.0,
            lon = 0.0,
            method = LocationMethod.Cell,
            accuracyMeters = 650f
        )

        assertFalse(RecordingTrackPointPolicy.shouldRecord(location))
    }

    @Test
    fun startsNewSegmentAfterLongLocationGap() {
        assertTrue(
            RecordingTrackPointPolicy.shouldStartNewSegment(
                elapsedSinceLastPointMillis = 6 * 60 * 1000L,
                recordingIntervalMillis = RecordingConfiguration.REQUEST_INTERVAL
            )
        )
    }

    @Test
    fun keepsSameSegmentForNormalIntervalDelay() {
        assertFalse(
            RecordingTrackPointPolicy.shouldStartNewSegment(
                elapsedSinceLastPointMillis = 2 * 60 * 1000L,
                recordingIntervalMillis = RecordingConfiguration.REQUEST_INTERVAL
            )
        )
    }

    private fun recordingLocation(
        lat: Double = 31.2304,
        lon: Double = 121.4737,
        method: LocationMethod,
        accuracyMeters: Float?
    ): RecordingLocation {
        return RecordingLocation(
            lat = lat,
            lon = lon,
            ele = null,
            time = Date(0L),
            status = RecordingLocationStatus(
                provider = LocationProviderName.Amap,
                method = method,
                accuracyMeters = accuracyMeters,
                signalStrength = LocationSignalStrength.Weak
            )
        )
    }
}
