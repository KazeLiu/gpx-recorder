package com.iboism.gpxrecorder.records.details

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iboism.gpxrecorder.R
import com.iboism.gpxrecorder.extensions.getThemeColor
import com.iboism.gpxrecorder.extensions.getThemeColorStateList
import com.iboism.gpxrecorder.model.GpxContent
import com.iboism.gpxrecorder.model.LastLocation
import com.iboism.gpxrecorder.model.Track
import com.iboism.gpxrecorder.model.TrackPoint
import com.iboism.gpxrecorder.recording.location.AmapRecordingLocationProvider
import com.iboism.gpxrecorder.settings.ThemePreference
import com.iboism.gpxrecorder.util.AmapCoordinateConverter
import com.iboism.gpxrecorder.util.DateTimeFormatHelper
import com.iboism.gpxrecorder.util.DP
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

internal class AmapRouteMapController(
    private val container: FrameLayout,
    private val gpxId: Long
) : RouteMapController, ViewTreeObserver.OnGlobalLayoutListener {
    private val mapView: MapView
    private var currentLocationButton: ImageButton? = null
    private val coordinateConverter: CoordinateConverter
    private var isLayoutReady = false
    private var isMapSetup = false
    private var map: AMap? = null
    private var currentLocationMarker: Marker? = null

    override var shouldDrawEnd = true
    override var shouldCenterOnLoad = true
    override var showCurrentLocationButton = false
        set(value) {
            field = value
            updateCurrentLocationButton()
        }
    override var shouldCenterOnCurrentLocationOnLoad = false
    override var trackPointEditingDelegate: TrackPointEditingDelegate? = null
    override val supportsTrackPointEditing = true
    private var isTrackPointEditingEnabled = false
    private val longPressHandler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(container.context).scaledTouchSlop
    private var pendingManualDrag: PendingManualDrag? = null
    private var activeManualDrag: ActiveManualDrag? = null
    private val editableTrackPointIconCache = mutableMapOf<Int, BitmapDescriptor>()
    private val currentLocationDescriptor: BitmapDescriptor by lazy { createCurrentLocationDescriptor() }
    private val trackPointMarkerIconSize: Pair<Int, Int> by lazy {
        val bitmap = bitmapFromVector(mapView.context, R.drawable.ic_draggable_track_point)
        (bitmap.width to bitmap.height).also { bitmap.recycle() }
    }

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
        updateCurrentLocationButton()
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
        cancelPendingManualDrag()
        finishActiveManualDrag(shouldSave = false)
        mapView.viewTreeObserver.removeOnGlobalLayoutListener(this)
        map?.clear()
        mapView.onDestroy()
    }

    override fun toggleMapType() {
        map?.let {
            it.mapType = if (it.mapType == AMap.MAP_TYPE_SATELLITE) {
                normalMapType()
            } else {
                AMap.MAP_TYPE_SATELLITE
            }
        }
    }

    override fun setTrackPointEditingEnabled(isEnabled: Boolean) {
        if (isTrackPointEditingEnabled == isEnabled) return
        isTrackPointEditingEnabled = isEnabled
        cancelPendingManualDrag()
        finishActiveManualDrag(shouldSave = false)
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
        map.mapType = normalMapType()
        if (mapView.context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            map.disableAutoCenteringLocation()
            map.isMyLocationEnabled = true
        }
        map.setOnMarkerClickListener { marker -> onMarkerClick(marker) }
        map.setOnMapTouchListener { event -> onMapTouch(event) }

        if (shouldCenterOnLoad) {
            val lastLocation = LastLocation.get()
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(toAmapLatLng(lastLocation.lat, lastLocation.lon), 17f))
        }
        loadGpxContent(gpxId)?.let { map.drawContent(it, shouldCenterOnLoad) }
        if (shouldCenterOnCurrentLocationOnLoad) {
            moveCameraToCurrentLocation()
        }
    }

    override fun onGlobalLayout() {
        isLayoutReady = true
        mapView.viewTreeObserver.removeOnGlobalLayoutListener(this)
        setupMapIfNeeded()
    }

    private fun AMap.drawContent(gpx: GpxContent, shouldCenter: Boolean) {
        val trackBounds = this.drawTracks(gpx.trackList.toList())
        if (showCurrentLocationButton) {
            this.drawCurrentLocationMarker()
        }

        if (trackBounds != null && shouldCenter) {
            this.moveCamera(CameraUpdateFactory.newLatLngBounds(trackBounds, 50))
        }
    }

    private fun AMap.drawTracks(tracks: List<Track>): LatLngBounds? {
        val allPoints: MutableList<LatLng> = mutableListOf()
        val boundsBuilder = LatLngBounds.Builder()
        clear()
        currentLocationMarker = null

        for (track in tracks) {
            for (segment in track.segments) {
                val points = segment.points.map { toAmapLatLng(it) }
                if (points.isEmpty()) continue
                allPoints.addAll(points)

                if (points.size < 2) continue
                this.addPolyline(
                    PolylineOptions()
                        .color(ContextCompat.getColor(mapView.context, R.color.white))
                        .width(16.5f)
                        .addAll(points)
                        .geodesic(true)
                )

                this.addPolyline(
                    PolylineOptions()
                        .color(mapView.context.getThemeColor(R.attr.gpxRouteLineColor))
                        .width(12f)
                        .addAll(points)
                        .geodesic(true)
                )
            }
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

    private fun AMap.drawEditableTrackPointMarkers(tracks: List<Track>) {
        var pointOrder = 1
        tracks.forEach { track ->
            track.segments.forEach { segment ->
                segment.points.forEach { point ->
                    val marker = this.addMarker(
                        MarkerOptions()
                            .position(toAmapLatLng(point))
                            .setFlat(false)
                            .title(mapView.context.getString(R.string.track_point))
                            .snippet(point.note.ifBlank { DateTimeFormatHelper.toReadableString(point.time) })
                            .icon(getNumberedTrackPointDescriptor(pointOrder))
                            .draggable(false)
                            .anchor(TRACK_POINT_IDLE_ANCHOR_U, TRACK_POINT_IDLE_ANCHOR_V)
                    )
                    marker.setObject(EditableMarker.TrackPoint(point.identifier))
                    pointOrder += 1
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

    private fun onMapTouch(event: MotionEvent) {
        if (!isTrackPointEditingEnabled) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> prepareManualDrag(event)
            MotionEvent.ACTION_MOVE -> updateManualDrag(event)
            MotionEvent.ACTION_UP -> finishManualDragTouch(event, shouldSave = true)
            MotionEvent.ACTION_CANCEL -> finishManualDragTouch(event, shouldSave = false)
        }
    }

    private fun prepareManualDrag(event: MotionEvent) {
        cancelPendingManualDrag()
        if (activeManualDrag != null) return
        val marker = findEditableTrackPointMarkerAt(event.x, event.y) ?: return
        val markerObject = marker.getObject() as? EditableMarker.TrackPoint ?: return
        pendingManualDrag = PendingManualDrag(marker, markerObject.pointId, event.x, event.y).also { pending ->
            pending.startRunnable = Runnable {
                if (pendingManualDrag !== pending) return@Runnable
                pendingManualDrag = null
                activeManualDrag = ActiveManualDrag(pending.marker, pending.pointId)
                map?.uiSettings?.setAllGesturesEnabled(false)
                pending.marker.setToTop()
                moveActiveMarkerTo(pending.startX, pending.startY)
            }
            longPressHandler.postDelayed(pending.startRunnable!!, ViewConfiguration.getLongPressTimeout().toLong())
        }
    }

    private fun updateManualDrag(event: MotionEvent) {
        val pending = pendingManualDrag
        if (pending != null && movedBeyondTouchSlop(pending.startX, pending.startY, event.x, event.y)) {
            cancelPendingManualDrag()
            return
        }
        if (activeManualDrag != null) {
            moveActiveMarkerTo(event.x, event.y)
        }
    }

    private fun finishManualDragTouch(event: MotionEvent, shouldSave: Boolean) {
        cancelPendingManualDrag()
        if (activeManualDrag != null) {
            moveActiveMarkerTo(event.x, event.y)
            finishActiveManualDrag(shouldSave)
        }
    }

    private fun moveActiveMarkerTo(screenX: Float, screenY: Float) {
        val marker = activeManualDrag?.marker ?: return
        val position = map?.projection?.fromScreenLocation(
            Point(screenX.roundToInt(), screenY.roundToInt())
        ) ?: return
        marker.position = position
    }

    private fun finishActiveManualDrag(shouldSave: Boolean) {
        val drag = activeManualDrag ?: return
        activeManualDrag = null
        map?.uiSettings?.setAllGesturesEnabled(true)
        if (!shouldSave) return
        val wgsLatLng = toWgsLatLng(drag.marker.position)
        TrackPointEditor.movePoint(drag.pointId, wgsLatLng.first, wgsLatLng.second)
        redraw()
        trackPointEditingDelegate?.onTrackPointEditingChanged()
    }

    private fun cancelPendingManualDrag() {
        pendingManualDrag?.startRunnable?.let { longPressHandler.removeCallbacks(it) }
        pendingManualDrag = null
    }

    private fun movedBeyondTouchSlop(startX: Float, startY: Float, x: Float, y: Float): Boolean {
        return abs(x - startX) > touchSlop || abs(y - startY) > touchSlop
    }

    private fun findEditableTrackPointMarkerAt(screenX: Float, screenY: Float): Marker? {
        val projection = map?.projection ?: return null
        val (iconWidth, iconHeight) = trackPointMarkerIconSize
        return map?.mapScreenMarkers
            ?.asSequence()
            ?.filter { it.getObject() is EditableMarker.TrackPoint }
            ?.mapNotNull { marker ->
                val markerScreen = projection.toScreenLocation(marker.position)
                val left = markerScreen.x - iconWidth * marker.anchorU
                val top = markerScreen.y - iconHeight * marker.anchorV
                val right = left + iconWidth
                val bottom = top + iconHeight
                if (screenX !in left..right || screenY !in top..bottom) return@mapNotNull null
                marker to hypot((markerScreen.x - screenX).toDouble(), (markerScreen.y - screenY).toDouble())
            }
            ?.minByOrNull { it.second }
            ?.first
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

    private fun showTrackPointDialog(pointId: Long) {
        val snapshot = TrackPointEditor.snapshot(pointId) ?: return
        val dialogView = LayoutInflater.from(mapView.context).inflate(R.layout.dialog_edit_track_point, null)
        val noteEditText = dialogView.findViewById<EditText>(R.id.track_point_note_edit_text).apply {
            setText(snapshot.note)
        }

        MaterialAlertDialogBuilder(mapView.context)
            .setTitle(R.string.edit_track_point)
            .setView(dialogView)
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

        AmapRecordingLocationProvider(mapView.context.applicationContext).requestCurrentLocation(
            onLocation = { location ->
                if (location.lat == 0.0 && location.lon == 0.0) {
                    moveCameraToLastLocation()
                    return@requestCurrentLocation
                }

                LastLocation.put(lat = location.lat, lon = location.lon)
                map?.drawCurrentLocationMarker(location.lat, location.lon)
                map?.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        toAmapLatLng(location.lat, location.lon),
                        17f
                    )
                )
            },
            onStatusChanged = {}
        )
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

    private fun normalMapType(): Int {
        return if (ThemePreference.isDarkModeEnabled(mapView.context)) {
            AMap.MAP_TYPE_NIGHT
        } else {
            AMap.MAP_TYPE_NORMAL
        }
    }

    private fun AMap.disableAutoCenteringLocation() {
        this.myLocationStyle = MyLocationStyle()
            .myLocationType(MyLocationStyle.LOCATION_TYPE_SHOW)
    }

    private fun AMap.drawCurrentLocationMarker(lat: Double? = null, lon: Double? = null) {
        val location = if (lat != null && lon != null) {
            lat to lon
        } else {
            LastLocation.get().let { it.lat to it.lon }
        }
        if (location.first == 0.0 && location.second == 0.0) return

        currentLocationMarker?.remove()
        currentLocationMarker = this.addMarker(
            MarkerOptions()
                .position(toAmapLatLng(location.first, location.second))
                .setFlat(true)
                .icon(currentLocationDescriptor)
                .anchor(.5f, .5f)
        )
    }

    private fun updateCurrentLocationButton() {
        if (!showCurrentLocationButton) {
            currentLocationButton?.let { container.removeView(it) }
            currentLocationButton = null
            return
        }

        if (currentLocationButton != null) return

        currentLocationButton = ImageButton(container.context).apply {
            setImageResource(R.drawable.ic_near_me)
            setBackgroundResource(R.drawable.md3_map_button_background)
            imageTintList = container.context.getThemeColorStateList(R.attr.colorPrimary)
            contentDescription = container.context.getString(R.string.return_to_current_location)
            alpha = .92f
            setOnClickListener { moveCameraToCurrentLocation() }
        }.also {
            container.addView(it, currentLocationButtonLayoutParams())
        }
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

    private fun getNumberedTrackPointDescriptor(pointOrder: Int): BitmapDescriptor {
        return editableTrackPointIconCache.getOrPut(pointOrder) {
            val (width, height) = trackPointMarkerIconSize
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val radius = minOf(width, height) * 0.38f
            val centerX = width / 2f
            val centerY = height / 2f

            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = mapView.context.getThemeColor(R.attr.gpxTrackPointDragColor)
                style = Paint.Style.FILL
            }
            val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = mapView.context.getThemeColor(R.attr.colorOnPrimary)
                strokeWidth = DP(2f, mapView.context).pxValue.toFloat()
                style = Paint.Style.STROKE
            }
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = mapView.context.getThemeColor(R.attr.gpxTrackPointIconOnColor)
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textSize = when {
                    pointOrder >= 100 -> DP(10f, mapView.context).pxValue.toFloat()
                    pointOrder >= 10 -> DP(12f, mapView.context).pxValue.toFloat()
                    else -> DP(14f, mapView.context).pxValue.toFloat()
                }
            }

            canvas.drawCircle(centerX, centerY, radius, fillPaint)
            canvas.drawCircle(centerX, centerY, radius, strokePaint)
            val textBaseline = centerY - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(pointOrder.toString(), centerX, textBaseline, textPaint)

            val descriptor = BitmapDescriptorFactory.fromBitmap(bitmap)
            bitmap.recycle()
            descriptor
        }
    }

    private fun createCurrentLocationDescriptor(): BitmapDescriptor {
        val size = DP(32f, mapView.context).pxValue
        val center = size / 2f
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = mapView.context.getThemeColor(R.attr.gpxRouteLineColor)
            alpha = 55
            style = Paint.Style.FILL
        }
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = mapView.context.getThemeColor(R.attr.gpxRouteLineColor)
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = DP(2f, mapView.context).pxValue.toFloat()
            style = Paint.Style.STROKE
        }
        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val arrowHalfWidth = DP(4f, mapView.context).pxValue.toFloat()
        val arrowTop = DP(7f, mapView.context).pxValue.toFloat()
        val arrowPath = Path().apply {
            moveTo(center, arrowTop)
            lineTo(center - arrowHalfWidth, center)
            lineTo(center + arrowHalfWidth, center)
            close()
        }

        canvas.drawCircle(center, center, DP(14f, mapView.context).pxValue.toFloat(), haloPaint)
        canvas.drawCircle(center, center, DP(8f, mapView.context).pxValue.toFloat(), dotPaint)
        canvas.drawCircle(center, center, DP(8f, mapView.context).pxValue.toFloat(), strokePaint)
        canvas.drawPath(arrowPath, arrowPaint)

        val descriptor = BitmapDescriptorFactory.fromBitmap(bitmap)
        bitmap.recycle()
        return descriptor
    }

    private sealed class EditableMarker {
        data class TrackPoint(val pointId: Long) : EditableMarker()
        data class InsertPoint(val previousPointId: Long) : EditableMarker()
    }

    private data class PendingManualDrag(
        val marker: Marker,
        val pointId: Long,
        val startX: Float,
        val startY: Float,
        var startRunnable: Runnable? = null
    )

    private data class ActiveManualDrag(
        val marker: Marker,
        val pointId: Long
    )

    private companion object {
        const val TRACK_POINT_IDLE_ANCHOR_U = .5f
        const val TRACK_POINT_IDLE_ANCHOR_V = .5f
    }
}
