package com.iboism.gpxrecorder.records.details

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.JointType.ROUND
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.iboism.gpxrecorder.R
import com.iboism.gpxrecorder.extensions.getThemeColor
import com.iboism.gpxrecorder.model.GpxContent
import com.iboism.gpxrecorder.model.LastLocation
import com.iboism.gpxrecorder.model.Track
import com.iboism.gpxrecorder.settings.ThemePreference
import com.iboism.gpxrecorder.util.DateTimeFormatHelper

internal class GoogleRouteMapController(
    private val container: FrameLayout,
    private val gpxId: Long
) : RouteMapController, OnMapReadyCallback, ViewTreeObserver.OnGlobalLayoutListener {
    private val mapView = MapView(container.context)
    private var isMapReady = false
    private var isLayoutReady = false
    private var isMapSetup = false
    private var map: GoogleMap? = null

    override var shouldDrawEnd = true
    override var shouldCenterOnLoad = true
    override var showCurrentLocationButton = false
    override var shouldCenterOnCurrentLocationOnLoad = false
    override var trackPointEditingDelegate: TrackPointEditingDelegate? = null
    override val supportsTrackPointEditing = false

    init {
        container.removeAllViews()
        container.addView(
            mapView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        mapView.viewTreeObserver.addOnGlobalLayoutListener(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)
    }

    override fun onStart() = mapView.onStart()
    override fun onResume() = mapView.onResume()
    override fun onPause() = mapView.onPause()
    override fun onLowMemory() = mapView.onLowMemory()
    override fun onSaveInstanceState(outState: Bundle) = mapView.onSaveInstanceState(outState)

    override fun redraw() {
        if (!isMapReady || !isLayoutReady || !isMapSetup) return
        loadGpxContent(gpxId)?.let { map?.drawContent(it, false) }
    }

    override fun onDestroy() {
        mapView.viewTreeObserver.removeOnGlobalLayoutListener(this)
        map?.clear()
        mapView.onDestroy()
    }

    override fun toggleMapType() {
        map?.let {
            it.mapType = if (it.mapType == GoogleMap.MAP_TYPE_SATELLITE) {
                normalMapType()
            } else {
                GoogleMap.MAP_TYPE_SATELLITE
            }
            applyMapStyle(it)
        }
    }

    override fun setTrackPointEditingEnabled(isEnabled: Boolean) {
        if (isEnabled) trackPointEditingDelegate?.onTrackPointEditingUnsupported()
    }

    @SuppressLint("MissingPermission")
    private fun setupMapIfNeeded() {
        if (!isMapReady || !isLayoutReady || isMapSetup) return
        val map = map ?: return
        isMapSetup = true

        MapsInitializer.initialize(mapView.context)
        map.uiSettings.isCompassEnabled = true
        map.uiSettings.isMapToolbarEnabled = true
        map.mapType = normalMapType()
        applyMapStyle(map)
        if (mapView.context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            map.isMyLocationEnabled = true
        }

        if (shouldCenterOnLoad) {
            val lastLocation = LastLocation.get()
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lastLocation.lat, lastLocation.lon), 17f))
        }
        loadGpxContent(gpxId)?.let { map.drawContent(it, shouldCenterOnLoad) }
        if (shouldCenterOnCurrentLocationOnLoad) {
            moveCameraToLastLocation()
        }
    }

    override fun onGlobalLayout() {
        isLayoutReady = true
        mapView.viewTreeObserver.removeOnGlobalLayoutListener(this)
        setupMapIfNeeded()
    }

    override fun onMapReady(map: GoogleMap) {
        isMapReady = true
        this.map = map
        setupMapIfNeeded()
    }

    private fun GoogleMap.drawContent(gpx: GpxContent, shouldCenter: Boolean) {
        val trackBounds = this.drawTracks(gpx.trackList.toList())

        if (trackBounds != null && shouldCenter) {
            this.moveCamera(CameraUpdateFactory.newLatLngBounds(trackBounds, 50))
        }
    }

    private fun GoogleMap.drawTracks(tracks: List<Track>): LatLngBounds? {
        val allPoints: MutableList<LatLng> = mutableListOf()
        val boundsBuilder = LatLngBounds.Builder()
        clear()

        for (track in tracks) {
            for (segment in track.segments) {
                val points = segment.points.map { LatLng(it.lat, it.lon) }
                if (points.isEmpty()) continue
                allPoints.addAll(points)

                if (points.size < 2) continue
                this.addPolyline(
                    PolylineOptions()
                        .color(ContextCompat.getColor(mapView.context, R.color.white))
                        .jointType(ROUND)
                        .width(16.5f)
                        .addAll(points)
                        .geodesic(true)
                )

                this.addPolyline(
                    PolylineOptions()
                        .color(mapView.context.getThemeColor(R.attr.gpxRouteLineColor))
                        .jointType(ROUND)
                        .width(12f)
                        .addAll(points)
                        .geodesic(true)
                )
            }
        }

        tracks.firstOrNull()?.segments?.firstOrNull()?.points?.firstOrNull()?.let {
            this.addMarker(MarkerOptions().position(LatLng(it.lat, it.lon))
                .flat(true)
                .title(mapView.context.getString(R.string.map_marker_start))
                .snippet(DateTimeFormatHelper.toReadableString(it.time))
                .icon(getBitmapDescriptor(R.drawable.ic_start_pt))
                .anchor(.5f, .5f))
        }

        if (shouldDrawEnd) {
            tracks.lastOrNull()?.segments?.lastOrNull()?.points?.lastOrNull()?.let {
                this.addMarker(MarkerOptions().position(LatLng(it.lat, it.lon))
                    .flat(true)
                    .title(mapView.context.getString(R.string.map_marker_end))
                    .snippet(DateTimeFormatHelper.toReadableString(it.time))
                    .icon(getBitmapDescriptor(R.drawable.ic_stop_pt))
                    .anchor(.5f, .5f))
            }
        }

        allPoints.forEach { boundsBuilder.include(it) }
        return if (allPoints.isNotEmpty()) boundsBuilder.build() else null
    }

    private fun moveCameraToLastLocation() {
        val lastLocation = LastLocation.get()
        map?.animateCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(lastLocation.lat, lastLocation.lon),
                17f
            )
        )
    }

    private fun normalMapType(): Int {
        return if (ThemePreference.isDarkModeEnabled(mapView.context)) {
            GoogleMap.MAP_TYPE_NORMAL
        } else {
            GoogleMap.MAP_TYPE_TERRAIN
        }
    }

    private fun applyMapStyle(map: GoogleMap) {
        if (map.mapType == GoogleMap.MAP_TYPE_SATELLITE || !ThemePreference.isDarkModeEnabled(mapView.context)) {
            map.setMapStyle(null)
            return
        }

        map.setMapStyle(MapStyleOptions.loadRawResourceStyle(mapView.context, R.raw.google_map_dark_style))
    }

    private fun getBitmapDescriptor(@DrawableRes id: Int): BitmapDescriptor {
        val bitmap = bitmapFromVector(mapView.context, id)
        val descriptor = BitmapDescriptorFactory.fromBitmap(bitmap)
        bitmap.recycle()
        return descriptor
    }
}
