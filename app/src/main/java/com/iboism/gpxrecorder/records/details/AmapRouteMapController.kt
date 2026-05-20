package com.iboism.gpxrecorder.records.details

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Point
import android.os.Bundle
import android.text.InputType
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.appcompat.app.AlertDialog
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
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.MyLocationStyle
import com.amap.api.maps.model.PolylineOptions
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.iboism.gpxrecorder.R
import com.iboism.gpxrecorder.model.GpxContent
import com.iboism.gpxrecorder.model.LastLocation
import com.iboism.gpxrecorder.model.Track
import com.iboism.gpxrecorder.model.TrackPoint
import com.iboism.gpxrecorder.model.Waypoint
import com.iboism.gpxrecorder.util.DateTimeFormatHelper
import com.iboism.gpxrecorder.util.DP
import kotlin.math.roundToInt

internal class AmapRouteMapController(
    private val container: FrameLayout,
    private val gpxId: Long
) : RouteMapController, ViewTreeObserver.OnGlobalLayoutListener {
    private val mapView: MapView
    private val currentLocationButton: ImageButton
    private val coordinateConverter: CoordinateConverter
    private var isLayoutReady = false
    private var isMapSetup = false
    private var map: AMap? = null

    override var shouldDrawEnd = true
    override var shouldCenterOnLoad = true
    override var trackPointEditingDelegate: TrackPointEditingDelegate? = null
    override val supportsTrackPointEditing = true
    private var isTrackPointEditingEnabled = false
    private var activeDraggedTrackPointMarker: Marker? = null
    private var latestDraggedTrackPointPosition: LatLng? = null

    init {
        MapsInitializer.updatePrivacyShow(container.context, true, true)
        MapsInitializer.updatePrivacyAgree(container.context, true)
        MapsInitializer.setApiKey(container.context.getString(R.string.amap_api_key))
        MapsInitializer.loadWorldVectorMap(true)

        mapView = MapView(container.context)
        coordinateConverter = CoordinateConverter(container.context)
        container.removeAllViews()
        container.addView(
            mapView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        currentLocationButton = ImageButton(container.context).apply {
            setImageResource(R.drawable.ic_near_me)
            setBackgroundColor(ContextCompat.getColor(container.context, R.color.nav_bar_surface))
            contentDescription = container.context.getString(R.string.return_to_current_location)
            alpha = .92f
            setOnClickListener { moveCameraToCurrentLocation() }
        }
        container.addView(currentLocationButton, currentLocationButtonLayoutParams())
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

    override fun setTrackPointEditingEnabled(isEnabled: Boolean) {
        if (isTrackPointEditingEnabled == isEnabled) return
        isTrackPointEditingEnabled = isEnabled
        activeDraggedTrackPointMarker = null
        latestDraggedTrackPointPosition = null
        map?.disableAutoCenteringLocation()
        redraw()
    }

    @SuppressLint("MissingPermission")
    private fun setupMapIfNeeded() {
        if (!isLayoutReady || isMapSetup) return
        val map = map ?: return
        isMapSetup = true

        map.uiSettings.isCompassEnabled = true
        map.uiSettings.isMyLocationButtonEnabled = false
        map.mapType = AMap.MAP_TYPE_NORMAL
        if (mapView.context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            map.disableAutoCenteringLocation()
            map.isMyLocationEnabled = true
        }
        map.setOnMarkerClickListener { marker -> onMarkerClick(marker) }
        map.setOnMarkerDragListener(object : AMap.OnMarkerDragListener {
            override fun onMarkerDragStart(marker: Marker) {
                onMarkerDragStarted(marker)
            }

            override fun onMarkerDrag(marker: Marker) = Unit

            override fun onMarkerDragEnd(marker: Marker) {
                onMarkerDragFinished(marker)
            }
        })
        map.setOnMapTouchListener { event -> onMapTouch(event) }

        if (shouldCenterOnLoad) {
            val lastLocation = LastLocation.get()
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(toAmapLatLng(lastLocation.lat, lastLocation.lon), 17f))
        }
        loadGpxContent(gpxId)?.let { map.drawContent(it, shouldCenterOnLoad) }
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
            if (!isTrackPointEditingEnabled) {
                this.addMarker(MarkerOptions().position(toAmapLatLng(it))
                    .setFlat(true)
                    .title(mapView.context.getString(R.string.map_marker_start))
                    .snippet(DateTimeFormatHelper.toReadableString(it.time))
                    .icon(getBitmapDescriptor(R.drawable.ic_start_pt))
                    .anchor(.5f, .5f))
            }
        }

        if (shouldDrawEnd && !isTrackPointEditingEnabled) {
            tracks.lastOrNull()?.segments?.lastOrNull()?.points?.lastOrNull()?.let {
                this.addMarker(MarkerOptions().position(toAmapLatLng(it))
                    .setFlat(true)
                    .title(mapView.context.getString(R.string.map_marker_end))
                    .snippet(DateTimeFormatHelper.toReadableString(it.time))
                    .icon(getBitmapDescriptor(R.drawable.ic_stop_pt))
                    .anchor(.5f, .5f))
            }
        }

        if (isTrackPointEditingEnabled) {
            drawEditableTrackPointMarkers(tracks)
            drawInsertPointMarkers(tracks)
        }

        allPoints.forEach { boundsBuilder.include(it) }
        return if (allPoints.isNotEmpty()) boundsBuilder.build() else null
    }

    private fun AMap.drawWaypoints(waypoints: List<Waypoint>) {
        if (isTrackPointEditingEnabled) return
        waypoints.forEach {
            this.addMarker(MarkerOptions().position(toAmapLatLng(it.lat, it.lon))
                .setFlat(true)
                .title(it.title)
                .snippet(it.desc)
                .icon(getBitmapDescriptor(R.drawable.ic_waypoint_pt))
                .anchor(.5f, .5f))
        }
    }

    private fun AMap.drawEditableTrackPointMarkers(tracks: List<Track>) {
        tracks.forEach { track ->
            track.segments.forEach { segment ->
                segment.points.forEach { point ->
                    val marker = this.addMarker(
                        MarkerOptions()
                            .position(toAmapLatLng(point))
                            .setFlat(true)
                            .title(mapView.context.getString(R.string.track_point))
                            .snippet(point.note.ifBlank { DateTimeFormatHelper.toReadableString(point.time) })
                            .icon(getBitmapDescriptor(R.drawable.ic_draggable_track_point))
                            .draggable(true)
                            .anchor(.5f, .5f)
                    )
                    marker.setObject(EditableMarker.TrackPoint(point.identifier))
                }
            }
        }
    }

    private fun AMap.drawInsertPointMarkers(tracks: List<Track>) {
        tracks.forEach { track ->
            track.segments.forEach { segment ->
                segment.points.zipWithNext { previousPoint, nextPoint ->
                    val previousLatLng = toAmapLatLng(previousPoint)
                    val nextLatLng = toAmapLatLng(nextPoint)
                    val marker = this.addMarker(
                        MarkerOptions()
                            .position(midpoint(previousLatLng, nextLatLng))
                            .setFlat(true)
                            .title(mapView.context.getString(R.string.insert_track_point))
                            .snippet(mapView.context.getString(R.string.insert_track_point_hint))
                            .icon(getBitmapDescriptor(R.drawable.ic_insert_track_point))
                            .anchor(.5f, .5f)
                    )
                    marker.setObject(EditableMarker.InsertPoint(previousPoint.identifier))
                }
            }
        }
    }

    private fun onMarkerClick(marker: Marker): Boolean {
        if (!isTrackPointEditingEnabled) return false
        return when (val markerObject = marker.getObject()) {
            is EditableMarker.TrackPoint -> {
                showTrackPointDialog(markerObject.pointId)
                true
            }
            is EditableMarker.InsertPoint -> {
                val wgsLatLng = toWgsLatLng(marker.position)
                TrackPointEditor.insertPointAfter(markerObject.previousPointId, wgsLatLng.first, wgsLatLng.second)
                redraw()
                trackPointEditingDelegate?.onTrackPointEditingChanged()
                true
            }
            else -> false
        }
    }

    private fun onMarkerDragStarted(marker: Marker) {
        if (!isTrackPointEditingEnabled) return
        if (marker.getObject() !is EditableMarker.TrackPoint) return
        activeDraggedTrackPointMarker = marker
        latestDraggedTrackPointPosition = marker.position
        marker.setToTop()
    }

    private fun onMapTouch(event: MotionEvent) {
        if (!isTrackPointEditingEnabled) return
        val marker = activeDraggedTrackPointMarker ?: return
        if (event.actionMasked != MotionEvent.ACTION_MOVE && event.actionMasked != MotionEvent.ACTION_UP) return
        val dragPosition = map?.projection?.fromScreenLocation(
            Point(event.x.roundToInt(), event.y.roundToInt())
        ) ?: return
        marker.position = dragPosition
        latestDraggedTrackPointPosition = dragPosition
    }

    private fun onMarkerDragFinished(marker: Marker) {
        if (!isTrackPointEditingEnabled) return
        val markerObject = marker.getObject() as? EditableMarker.TrackPoint ?: return
        val finalPosition = latestDraggedTrackPointPosition ?: marker.position
        val wgsLatLng = toWgsLatLng(finalPosition)
        activeDraggedTrackPointMarker = null
        latestDraggedTrackPointPosition = null
        TrackPointEditor.movePoint(markerObject.pointId, wgsLatLng.first, wgsLatLng.second)
        redraw()
        trackPointEditingDelegate?.onTrackPointEditingChanged()
    }

    private fun showTrackPointDialog(pointId: Long) {
        val snapshot = TrackPointEditor.snapshot(pointId) ?: return
        val noteEditText = EditText(mapView.context).apply {
            setText(snapshot.note)
            hint = mapView.context.getString(R.string.track_point_note_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
        }

        AlertDialog.Builder(mapView.context)
            .setTitle(R.string.edit_track_point)
            .setView(noteEditText)
            .setPositiveButton(R.string.save_note) { _, _ ->
                TrackPointEditor.updateNote(pointId, noteEditText.text.toString())
                redraw()
                trackPointEditingDelegate?.onTrackPointEditingChanged()
            }
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.delete_track_point) { _, _ ->
                TrackPointEditor.deletePoint(pointId)
                redraw()
                trackPointEditingDelegate?.onTrackPointEditingChanged()
            }
            .create()
            .show()
    }

    private fun midpoint(previous: LatLng, next: LatLng): LatLng {
        return LatLng(
            (previous.latitude + next.latitude) / 2.0,
            (previous.longitude + next.longitude) / 2.0
        )
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

    private fun toWgsLatLng(latLng: LatLng): Pair<Double, Double> {
        return AmapCoordinateConverter.gcjToWgs(latLng.latitude, latLng.longitude)
    }

    @SuppressLint("MissingPermission")
    private fun moveCameraToCurrentLocation() {
        if (mapView.context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            moveCameraToLastLocation()
            return
        }

        LocationServices.getFusedLocationProviderClient(mapView.context)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                if (location == null || location.isZeroCoordinate()) {
                    moveCameraToLastLocation()
                    return@addOnSuccessListener
                }

                LastLocation.put(lat = location.latitude, lon = location.longitude)
                map?.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        toAmapLatLng(location.latitude, location.longitude),
                        17f
                    )
                )
            }
            .addOnFailureListener {
                moveCameraToLastLocation()
            }
    }

    private fun moveCameraToLastLocation() {
        val lastLocation = LastLocation.get()
        map?.animateCamera(
            CameraUpdateFactory.newLatLngZoom(
                toAmapLatLng(lastLocation.lat, lastLocation.lon),
                17f
            )
        )
    }

    private fun android.location.Location.isZeroCoordinate(): Boolean {
        return latitude == 0.0 && longitude == 0.0
    }

    private fun AMap.disableAutoCenteringLocation() {
        this.myLocationStyle = MyLocationStyle()
            .myLocationType(MyLocationStyle.LOCATION_TYPE_SHOW)
    }

    private fun currentLocationButtonLayoutParams(): FrameLayout.LayoutParams {
        val size = DP(48f, container.context).pxValue
        val margin = DP(12f, container.context).pxValue
        return FrameLayout.LayoutParams(size, size).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.END
            setMargins(margin, margin, margin, margin)
        }
    }

    private fun getBitmapDescriptor(@DrawableRes id: Int): BitmapDescriptor {
        val bitmap = bitmapFromVector(mapView.context, id)
        val descriptor = BitmapDescriptorFactory.fromBitmap(bitmap)
        bitmap.recycle()
        return descriptor
    }

    private sealed class EditableMarker {
        data class TrackPoint(val pointId: Long) : EditableMarker()
        data class InsertPoint(val previousPointId: Long) : EditableMarker()
    }
}
