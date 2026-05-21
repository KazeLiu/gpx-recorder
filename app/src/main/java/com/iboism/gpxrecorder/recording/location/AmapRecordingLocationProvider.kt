package com.iboism.gpxrecorder.recording.location

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.util.Log
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.iboism.gpxrecorder.model.RecordingConfiguration
import com.iboism.gpxrecorder.util.AmapCoordinateConverter
import java.util.Date

class AmapRecordingLocationProvider(
    private val context: Context
) : RecordingLocationProvider {
    private var locationClient: AMapLocationClient? = null
    private var latestStatus = RecordingLocationStatus.waiting(LocationProviderName.Amap)

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
        val client = createClient(
            config.interval,
            isOnceLocation = false,
            onLocation = onLocation,
            onStatusChanged = onStatusChanged
        ) ?: return
        locationClient = client
        client.enableBackgroundLocation(notificationId, notification)
        client.startLocation()
    }

    @SuppressLint("MissingPermission")
    override fun requestCurrentLocation(
        onLocation: (RecordingLocation) -> Unit,
        onStatusChanged: (RecordingLocationStatus) -> Unit
    ) {
        val client = createClient(
            intervalMillis = RecordingConfiguration.REQUEST_INTERVAL,
            isOnceLocation = true,
            onLocation = onLocation,
            onStatusChanged = onStatusChanged,
            destroyAfterFirstCallback = true
        ) ?: return
        client.startLocation()
    }

    override fun stop() {
        locationClient?.disableBackgroundLocation(false)
        locationClient?.stopLocation()
        locationClient?.onDestroy()
        locationClient = null
    }

    override fun destroy() {
        stop()
    }

    private fun createClient(
        intervalMillis: Long,
        isOnceLocation: Boolean,
        onLocation: (RecordingLocation) -> Unit,
        onStatusChanged: (RecordingLocationStatus) -> Unit,
        destroyAfterFirstCallback: Boolean = false
    ): AMapLocationClient? {
        val client = try {
            AMapLocationClient(context.applicationContext)
        } catch (e: Exception) {
            latestStatus = RecordingLocationStatus(
                provider = LocationProviderName.Amap,
                method = LocationMethod.Unknown,
                errorMessage = e.localizedMessage ?: e.javaClass.simpleName
            )
            onStatusChanged(latestStatus)
            return null
        }

        client.setLocationOption(locationOption(intervalMillis, isOnceLocation))
        client.setLocationListener { location ->
            val status = location.toStatus()
            latestStatus = status
            onStatusChanged(status)
            location.toRecordingLocation()?.let(onLocation)
            if (destroyAfterFirstCallback) {
                client.stopLocation()
                client.onDestroy()
            }
        }
        return client
    }

    private fun locationOption(intervalMillis: Long, isOnceLocation: Boolean): AMapLocationClientOption {
        return AMapLocationClientOption().apply {
            locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            this.interval = intervalMillis
            isOnceLocationLatest = isOnceLocation
            isNeedAddress = false
            isGpsFirst = true
            isWifiScan = true
            isMockEnable = false
            isLocationCacheEnable = !isOnceLocation
            setSensorEnable(true)
            setOffset(true)
        }
    }

    private fun AMapLocation.toRecordingLocation(): RecordingLocation? {
        if (errorCode != AMapLocation.LOCATION_SUCCESS) return null
        val (wgsLat, wgsLon) = if (coordType == AMapLocation.COORD_TYPE_GCJ02 || isOffset) {
            AmapCoordinateConverter.gcjToWgs(latitude, longitude)
        } else {
            latitude to longitude
        }
        return RecordingLocation(
            lat = wgsLat,
            lon = wgsLon,
            ele = altitude.takeIf { hasAltitude() },
            time = Date(time.takeIf { it > 0L } ?: System.currentTimeMillis()),
            status = toStatus()
        )
    }

    private fun AMapLocation.toStatus(): RecordingLocationStatus {
        if (errorCode != AMapLocation.LOCATION_SUCCESS) {
            val formattedError = AmapLocationErrorFormatter.format(
                errorCode = errorCode,
                errorInfo = errorInfo,
                locationDetail = locationDetail
            )
            Log.w(TAG, formattedError)
            return RecordingLocationStatus(
                provider = LocationProviderName.Amap,
                method = LocationMethod.Unknown,
                errorMessage = formattedError
            )
        }

        val accuracy = accuracy.takeIf { hasAccuracy() }
        val method = when (locationType) {
            AMapLocation.LOCATION_TYPE_GPS -> LocationMethod.Gps
            AMapLocation.LOCATION_TYPE_WIFI -> LocationMethod.Wifi
            AMapLocation.LOCATION_TYPE_CELL -> LocationMethod.Cell
            AMapLocation.LOCATION_TYPE_AMAP -> LocationMethod.AmapNetwork
            AMapLocation.LOCATION_TYPE_FIX_CACHE,
            AMapLocation.LOCATION_TYPE_LAST_LOCATION_CACHE,
            AMapLocation.LOCATION_TYPE_FAST -> LocationMethod.Cache
            else -> LocationMethod.Unknown
        }
        return RecordingLocationStatus(
            provider = LocationProviderName.Amap,
            method = method,
            accuracyMeters = accuracy,
            satellites = satellites.takeIf { it > 0 },
            signalStrength = signalStrengthFromAccuracy(accuracy)
        )
    }

    companion object {
        private const val TAG = "AmapLocationProvider"
    }
}
