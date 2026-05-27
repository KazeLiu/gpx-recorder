package com.iboism.gpxrecorder.recording

import com.iboism.gpxrecorder.recording.location.RecordingLocation
import kotlin.math.roundToInt

internal object RecordingTrackPointNoteFormatter {
    fun accuracyNote(location: RecordingLocation): String {
        val accuracy = location.status.accuracyMeters ?: return "定位精度：未知"
        return "定位精度：约 ${accuracy.roundToInt()} 米"
    }
}
