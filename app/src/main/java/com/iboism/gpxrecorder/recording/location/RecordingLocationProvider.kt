package com.iboism.gpxrecorder.recording.location

import android.app.Notification
import com.iboism.gpxrecorder.model.RecordingConfiguration

interface RecordingLocationProvider {
    val status: RecordingLocationStatus

    fun start(
        config: RecordingConfiguration,
        notificationId: Int,
        notification: Notification,
        onLocation: (RecordingLocation) -> Unit,
        onStatusChanged: (RecordingLocationStatus) -> Unit
    )

    fun requestCurrentLocation(
        onLocation: (RecordingLocation) -> Unit,
        onStatusChanged: (RecordingLocationStatus) -> Unit
    )

    fun stop()
    fun destroy()
}
