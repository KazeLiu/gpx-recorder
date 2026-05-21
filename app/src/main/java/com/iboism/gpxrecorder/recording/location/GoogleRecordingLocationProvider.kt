package com.iboism.gpxrecorder.recording.location

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.location.Location
import android.os.SystemClock
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.iboism.gpxrecorder.model.RecordingConfiguration
import java.util.Date

class GoogleRecordingLocationProvider(
    private val context: Context
) : RecordingLocationProvider {
    private val fusedLocation by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }
    private var locationCallback: LocationCallback? = null
    private var latestStatus = RecordingLocationStatus.waiting(LocationProviderName.Google)

    override val status: RecordingLocationStatus
        get() = latestStatus

    @SuppressLint("MissingPermission")
    override fun start(
        config: RecordingConfiguration,
        notificationId: Int,
        notification: Notification,
        onLocation: (RecordingLocation) -> Unit,
        onStatusChanged: (RecordingLocationStatus) -> Unit
    ) {
        stop()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    val recordedLocation = location.toRecordingLocation()
                    latestStatus = recordedLocation.status
                    onStatusChanged(latestStatus)
                    onLocation(recordedLocation)
                }
            }
        }
        fusedLocation.requestLocationUpdates(
            config.locationRequest(),
            locationCallback ?: return,
            context.mainLooper
        )
    }

    @SuppressLint("MissingPermission")
    override fun requestCurrentLocation(
        onLocation: (RecordingLocation) -> Unit,
        onStatusChanged: (RecordingLocationStatus) -> Unit
    ) {
        fusedLocation
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                location?.let {
                    val recordedLocation = it.toRecordingLocation()
                    latestStatus = recordedLocation.status
                    onStatusChanged(latestStatus)
                    onLocation(recordedLocation)
                }
            }
    }

    override fun stop() {
        locationCallback?.let { fusedLocation.removeLocationUpdates(it) }
        locationCallback = null
    }

    override fun destroy() {
        stop()
    }

    private fun Location.toRecordingLocation(): RecordingLocation {
        val status = RecordingLocationStatus(
            provider = LocationProviderName.Google,
            method = LocationMethod.Fused,
            accuracyMeters = accuracy.takeIf { hasAccuracy() },
            signalStrength = signalStrengthFromAccuracy(accuracy.takeIf { hasAccuracy() })
        )
        val locAgeNanos = SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos
        val fixTime = Date().toInstant().minusNanos(locAgeNanos)
        return RecordingLocation(
            lat = latitude,
            lon = longitude,
            ele = altitude.takeIf { hasAltitude() },
            time = Date.from(fixTime),
            status = status
        )
    }
}
