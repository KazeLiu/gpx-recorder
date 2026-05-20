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
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.CoordinateConverter
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.BitmapDescriptor
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions
import com.iboism.gpxrecorder.R
import com.iboism.gpxrecorder.model.GpxContent
import com.iboism.gpxrecorder.model.LastLocation
import com.iboism.gpxrecorder.model.Track
import com.iboism.gpxrecorder.model.TrackPoint
import com.iboism.gpxrecorder.model.Waypoint
import com.iboism.gpxrecorder.util.DateTimeFormatHelper

internal class AmapRouteMapController(
    private val container: FrameLayout,
    private val gpxId: Long
) : RouteMapController, ViewTreeObserver.OnGlobalLayoutListener {
    private val mapView: MapView
    private val coordinateConverter: CoordinateConverter
    private var isLayoutReady = false
    private var isMapSetup = false
    private var map: AMap? = null

    override var shouldDrawEnd = true

    init {
        MapsInitializer.updatePrivacyShow(container.context, true, true)
        MapsInitializer.updatePrivacyAgree(container.context, true)
        MapsInitializer.setApiKey(container.context.getString(R.string.amap_api_key))

        mapView = MapView(container.context)
        coordinateConverter = CoordinateConverter(container.context)
        container.removeAllViews()
        container.addView(
            mapView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        mapView.viewTreeObserver.addOnGlobalLayoutListener(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        mapView.onCreate(savedInstanceState)
        map = mapView.map
        setupMapIfNeeded()
    }

    override fun onStart() = Unit
    override fun onResume() = mapView.onResume()
    override fun onPause() = mapView.onPause()
    override fun onLowMemory() = mapView.onLowMemory()
    override fun onSaveInstanceState(outState: Bundle) = mapView.onSaveInstanceState(outState)

    override fun redraw() {
        if (!isLayoutReady || !isMapSetup) return
        loadGpxContent(gpxId)?.let { map?.drawContent(it, false) }
    }

    override fun onDestroy() {
        mapView.viewTreeObserver.removeOnGlobalLayoutListener(this)
        map?.clear()
        mapView.onDestroy()
    }

    override fun toggleMapType() {
        map?.let {
            it.mapType = if (it.mapType == AMap.MAP_TYPE_SATELLITE) {
                AMap.MAP_TYPE_NORMAL
            } else {
                AMap.MAP_TYPE_SATELLITE
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupMapIfNeeded() {
        if (!isLayoutReady || isMapSetup) return
        val map = map ?: return
        isMapSetup = true

        map.uiSettings.isCompassEnabled = true
        map.uiSettings.isMyLocationButtonEnabled = true
        map.mapType = AMap.MAP_TYPE_NORMAL
        if (mapView.context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            map.isMyLocationEnabled = true
        }

        val lastLocation = LastLocation.get()
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(toAmapLatLng(lastLocation.lat, lastLocation.lon), 17f))
        loadGpxContent(gpxId)?.let { map.drawContent(it, true) }
    }

    override fun onGlobalLayout() {
        isLayoutReady = true
        mapView.viewTreeObserver.removeOnGlobalLayoutListener(this)
        setupMapIfNeeded()
    }

    private fun AMap.drawContent(gpx: GpxContent, shouldCenter: Boolean) {
        val trackBounds = this.drawTracks(gpx.trackList.toList())
        this.drawWaypoints(gpx.waypointList.toList())

        if (trackBounds != null && shouldCenter) {
            this.moveCamera(CameraUpdateFactory.newLatLngBounds(trackBounds, 50))
        }
    }

    private fun AMap.drawTracks(tracks: List<Track>): LatLngBounds? {
        val allPoints: MutableList<LatLng> = mutableListOf()
        val boundsBuilder = LatLngBounds.Builder()
        clear()

        tracks.forEach { track ->
            val points = track.segments.flatMap { segment ->
                segment.points.map { toAmapLatLng(it) }
            }
            if (points.isEmpty()) return@forEach
            allPoints.addAll(points)

            this.addPolyline(
                PolylineOptions()
                    .color(ContextCompat.getColor(mapView.context, R.color.white))
                    .width(16.5f)
                    .addAll(points)
                    .geodesic(true)
            )

            this.addPolyline(
                PolylineOptions()
                    .color(ContextCompat.getColor(mapView.context, R.color.google_light_blue))
                    .width(12f)
                    .addAll(points)
                    .geodesic(true)
            )
        }

        tracks.firstOrNull()?.segments?.firstOrNull()?.points?.firstOrNull()?.let {
            this.addMarker(MarkerOptions().position(toAmapLatLng(it))
                .setFlat(true)
                .title(mapView.context.getString(R.string.map_marker_start))
                .snippet(DateTimeFormatHelper.toReadableString(it.time))
                .icon(getBitmapDescriptor(R.drawable.ic_start_pt))
                .anchor(.5f, .5f))
        }

        if (shouldDrawEnd) {
            tracks.lastOrNull()?.segments?.lastOrNull()?.points?.lastOrNull()?.let {
                this.addMarker(MarkerOptions().position(toAmapLatLng(it))
                    .setFlat(true)
                    .title(mapView.context.getString(R.string.map_marker_end))
                    .snippet(DateTimeFormatHelper.toReadableString(it.time))
                    .icon(getBitmapDescriptor(R.drawable.ic_stop_pt))
                    .anchor(.5f, .5f))
            }
        }

        allPoints.forEach { boundsBuilder.include(it) }
        return if (allPoints.isNotEmpty()) boundsBuilder.build() else null
    }

    private fun AMap.drawWaypoints(waypoints: List<Waypoint>) {
        waypoints.forEach {
            this.addMarker(MarkerOptions().position(toAmapLatLng(it.lat, it.lon))
                .setFlat(true)
                .title(it.title)
                .snippet(it.desc)
                .icon(getBitmapDescriptor(R.drawable.ic_waypoint_pt))
                .anchor(.5f, .5f))
        }
    }

    private fun toAmapLatLng(point: TrackPoint): LatLng {
        return toAmapLatLng(point.lat, point.lon)
    }

    private fun toAmapLatLng(lat: Double, lon: Double): LatLng {
        val source = LatLng(lat, lon)
        if (!CoordinateConverter.isAMapDataAvailable(lat, lon)) return source
        return coordinateConverter
            .from(CoordinateConverter.CoordType.GPS)
            .coord(source)
            .convert()
    }

    private fun getBitmapDescriptor(@DrawableRes id: Int): BitmapDescriptor {
        val bitmap = bitmapFromVector(mapView.context, id)
        val descriptor = BitmapDescriptorFactory.fromBitmap(bitmap)
        bitmap.recycle()
        return descriptor
    }
}
