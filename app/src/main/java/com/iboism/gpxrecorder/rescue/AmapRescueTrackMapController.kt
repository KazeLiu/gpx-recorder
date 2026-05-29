package com.iboism.gpxrecorder.rescue

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.MyLocationStyle
import com.amap.api.maps.model.Poi
import com.amap.api.maps.model.PolylineOptions
import com.iboism.gpxrecorder.R
import com.iboism.gpxrecorder.extensions.getThemeColor
import com.iboism.gpxrecorder.model.LastLocation
import com.iboism.gpxrecorder.model.RescueDraftStep
import com.iboism.gpxrecorder.model.RescueTrackDraft
import com.iboism.gpxrecorder.settings.ThemePreference
import com.iboism.gpxrecorder.util.AmapCoordinateConverter
import com.iboism.gpxrecorder.util.DP

class AmapRescueTrackMapController(
    private val container: FrameLayout,
    private val listener: Listener
) : ViewTreeObserver.OnGlobalLayoutListener {
    interface Listener {
        fun onMapTapped(lat: Double, lon: Double)
        fun onPoiTapped(name: String, lat: Double, lon: Double)
        fun onAnchorTapped(anchorId: Long)
        fun onAnchorDragged(anchorId: Long, lat: Double, lon: Double)
    }

    private val mapView: MapView
    private var map: AMap? = null
    private var isLayoutReady = false
    private var isMapSetup = false
    private var pendingDraft: RescueTrackDraft? = null
    private var pendingActiveSegmentIndex: Int = -1
    private val waypointIconCache = mutableMapOf<Int, com.amap.api.maps.model.BitmapDescriptor>()

    init {
        MapsInitializer.updatePrivacyShow(container.context, true, true)
        MapsInitializer.updatePrivacyAgree(container.context, true)
        MapsInitializer.setApiKey(container.context.getString(R.string.amap_api_key))
        MapsInitializer.loadWorldVectorMap(true)

        mapView = MapView(container.context)
        container.removeAllViews()
        container.addView(
            mapView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        mapView.viewTreeObserver.addOnGlobalLayoutListener(this)
    }

    fun onCreate(savedInstanceState: Bundle?) {
        mapView.onCreate(savedInstanceState)
        map = mapView.map
        setupMapIfNeeded()
    }

    fun onStart() = Unit
    fun onResume() = mapView.onResume()
    fun onPause() = mapView.onPause()
    fun onLowMemory() = mapView.onLowMemory()
    fun onSaveInstanceState(outState: Bundle) = mapView.onSaveInstanceState(outState)

    fun onDestroy() {
        mapView.viewTreeObserver.removeOnGlobalLayoutListener(this)
        map?.clear()
        mapView.onDestroy()
    }

    fun render(draft: RescueTrackDraft, activeSegmentIndex: Int = -1) {
        pendingDraft = draft
        pendingActiveSegmentIndex = activeSegmentIndex
        if (!isLayoutReady || !isMapSetup) return
        drawDraft(draft, activeSegmentIndex)
    }

    fun focusOn(lat: Double, lon: Double) {
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(toAmapLatLng(lat, lon), 16f))
    }

    fun focusOnSegment(draft: RescueTrackDraft, segmentIndex: Int) {
        val map = map ?: return
        val anchors = draft.anchors.filterNotNull().associateBy { it.identifier }
        val segment = draft.segments.filterNotNull().sortedBy { it.order }.getOrNull(segmentIndex) ?: return
        val route = RescueTrackGenerator.decodePolyline(segment.selectedRoutePolyline).ifEmpty {
            val from = anchors[segment.fromAnchorId] ?: return
            val to = anchors[segment.toAnchorId] ?: return
            buildList {
                add(RoutePoint(from.lat, from.lon))
                addAll(RescueDraftRepository.waypointsFromJson(segment.waypointsJson).map { RoutePoint(it.lat, it.lon) })
                add(RoutePoint(to.lat, to.lon))
            }
        }
        if (route.size < 2) return
        val bounds = LatLngBounds.Builder()
        route.map { toAmapLatLng(it.lat, it.lon) }.forEach { bounds.include(it) }
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 120))
    }

    fun focusOnWaypoint(lat: Double, lon: Double) {
        val map = map ?: return
        val position = toAmapLatLng(lat, lon)
        map.animateCamera(CameraUpdateFactory.newLatLng(position))
    }

    @SuppressLint("MissingPermission")
    private fun setupMapIfNeeded() {
        if (!isLayoutReady || isMapSetup) return
        val map = map ?: return
        isMapSetup = true
        map.uiSettings.isCompassEnabled = true
        map.uiSettings.isMyLocationButtonEnabled = true
        map.mapType = normalMapType()
        if (mapView.context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            map.myLocationStyle = MyLocationStyle().myLocationType(MyLocationStyle.LOCATION_TYPE_SHOW)
            map.isMyLocationEnabled = true
        }
        map.setOnMapClickListener { latLng ->
            val (lat, lon) = AmapCoordinateConverter.gcjToWgs(latLng.latitude, latLng.longitude)
            listener.onMapTapped(lat, lon)
        }
        map.setOnPOIClickListener { poi ->
            handlePoiClick(poi)
        }
        map.setOnMarkerClickListener { marker ->
            val anchorId = marker.getObject() as? Long ?: return@setOnMarkerClickListener false
            listener.onAnchorTapped(anchorId)
            true
        }
        map.setOnMarkerDragListener(object : AMap.OnMarkerDragListener {
            override fun onMarkerDragStart(marker: Marker?) = Unit
            override fun onMarkerDrag(marker: Marker?) = Unit
            override fun onMarkerDragEnd(marker: Marker?) {
                val target = marker ?: return
                val anchorId = target.getObject() as? Long ?: return
                val (lat, lon) = AmapCoordinateConverter.gcjToWgs(target.position.latitude, target.position.longitude)
                listener.onAnchorDragged(anchorId, lat, lon)
            }
        })

        val lastLocation = LastLocation.get()
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(toAmapLatLng(lastLocation.lat, lastLocation.lon), 15f))
        pendingDraft?.let { drawDraft(it, pendingActiveSegmentIndex) }
    }

    private fun handlePoiClick(poi: Poi?) {
        val coordinate = poi?.coordinate ?: return
        val (lat, lon) = AmapCoordinateConverter.gcjToWgs(coordinate.latitude, coordinate.longitude)
        listener.onPoiTapped(poi.name.orEmpty().ifBlank { "地图文字" }, lat, lon)
    }

    override fun onGlobalLayout() {
        isLayoutReady = true
        mapView.viewTreeObserver.removeOnGlobalLayoutListener(this)
        setupMapIfNeeded()
    }

    private fun drawDraft(draft: RescueTrackDraft, activeSegmentIndex: Int) {
        val map = map ?: return
        val boundsBuilder = LatLngBounds.Builder()
        var hasBounds = false
        map.clear()

        val anchors = draft.anchors.filterNotNull().sortedBy { it.order }
        val anchorsById = anchors.associateBy { it.identifier }
        val previewPoints = draft.generatedPreviewPoints.filterNotNull().sortedBy { it.order }
        val routeForSegment: (com.iboism.gpxrecorder.model.RescueRouteSegment) -> List<RoutePoint> = { segment ->
            RescueTrackGenerator.decodePolyline(segment.selectedRoutePolyline).ifEmpty {
                val from = anchorsById[segment.fromAnchorId]
                val to = anchorsById[segment.toAnchorId]
                if (from == null || to == null) {
                    emptyList()
                } else {
                    buildList {
                        add(RoutePoint(from.lat, from.lon))
                        addAll(RescueDraftRepository.waypointsFromJson(segment.waypointsJson).map { RoutePoint(it.lat, it.lon) })
                        add(RoutePoint(to.lat, to.lon))
                    }
                }
            }
        }
        if (previewPoints.size >= 2) {
            val points = previewPoints.map { toAmapLatLng(it.lat, it.lon) }
            points.forEach {
                hasBounds = true
                boundsBuilder.include(it)
            }
            drawPolyline(
                points,
                mapView.context.getThemeColor(R.attr.gpxRouteLineColor),
                12f
            )
        } else if (draft.currentStep == RescueDraftStep.PLANNING.name || draft.currentStep == RescueDraftStep.GENERATED.name) {
            val segments = draft.segments.filterNotNull().sortedBy { it.order }
            segments.forEach { segment ->
                val route = routeForSegment(segment)
                if (route.size >= 2) {
                    drawPolyline(
                        route.map { toAmapLatLng(it.lat, it.lon) },
                        mapView.context.getThemeColor(R.attr.gpxRouteLineColor),
                        12f
                    )
                }
            }
            val boundsRoutes = if (draft.currentStep == RescueDraftStep.PLANNING.name) {
                listOfNotNull(segments.getOrNull(activeSegmentIndex)?.let(routeForSegment))
            } else {
                segments.map(routeForSegment)
            }
            boundsRoutes.flatten().map { toAmapLatLng(it.lat, it.lon) }.forEach {
                hasBounds = true
                boundsBuilder.include(it)
            }
            if (!hasBounds && anchors.isNotEmpty()) {
                anchors.map { toAmapLatLng(it.lat, it.lon) }.forEach {
                    hasBounds = true
                    boundsBuilder.include(it)
                }
            }
        } else if (anchors.size >= 2) {
            val points = anchors.map { toAmapLatLng(it.lat, it.lon) }
            points.forEach {
                hasBounds = true
                boundsBuilder.include(it)
            }
            drawPolyline(
                points,
                mapView.context.getThemeColor(R.attr.colorPrimary),
                8f
            )
        }

        anchors.forEachIndexed { index, anchor ->
            val position = toAmapLatLng(anchor.lat, anchor.lon)
            if (draft.currentStep != RescueDraftStep.PLANNING.name && draft.currentStep != RescueDraftStep.GENERATED.name) {
                hasBounds = true
                boundsBuilder.include(position)
            }
            map.addMarker(
                MarkerOptions()
                    .position(position)
                    .title("锚点 ${index + 1}")
                    .snippet(anchor.time ?: "未设置时间")
                    .draggable(!anchor.locked)
                    .icon(numberedAnchorIcon(index + 1, anchor.locked))
            )?.setObject(anchor.identifier)
        }

        if (draft.currentStep == RescueDraftStep.PLANNING.name) {
            draft.segments.filterNotNull().sortedBy { it.order }.getOrNull(activeSegmentIndex)?.let { segment ->
                RescueDraftRepository.waypointsFromJson(segment.waypointsJson).forEachIndexed { index, waypoint ->
                    val position = toAmapLatLng(waypoint.lat, waypoint.lon)
                    hasBounds = true
                    boundsBuilder.include(position)
                    map.addMarker(
                        MarkerOptions()
                            .position(position)
                            .title("途经点 ${index + 1}")
                            .icon(numberedWaypointIcon(index + 1))
                            .zIndex(10f)
                    )
                }
            }
        }

        if (hasBounds) {
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 80))
        }
    }

    private fun drawPolyline(points: List<LatLng>, color: Int, width: Float) {
        if (points.size < 2) return
        map?.addPolyline(
            PolylineOptions()
                .color(ContextCompat.getColor(mapView.context, R.color.white))
                .width(width + 4f)
                .addAll(points)
                .geodesic(true)
        )
        map?.addPolyline(
            PolylineOptions()
                .color(color)
                .width(width)
                .addAll(points)
                .geodesic(true)
        )
    }

    private fun toAmapLatLng(lat: Double, lon: Double): LatLng {
        val (gcjLat, gcjLon) = AmapCoordinateConverter.wgsToGcj(lat, lon)
        return LatLng(gcjLat, gcjLon)
    }

    private fun normalMapType(): Int {
        return if (ThemePreference.isDarkModeEnabled(mapView.context)) {
            AMap.MAP_TYPE_NIGHT
        } else {
            AMap.MAP_TYPE_NORMAL
        }
    }

    private fun numberedWaypointIcon(number: Int): com.amap.api.maps.model.BitmapDescriptor {
        val cacheKey = number
        return waypointIconCache.getOrPut(cacheKey) {
            numberedCircleIcon(number, mapView.context.getThemeColor(R.attr.colorSecondary))
        }
    }

    private fun numberedAnchorIcon(number: Int, locked: Boolean): com.amap.api.maps.model.BitmapDescriptor {
        val cacheKey = 10_000 + number + if (locked) 1_000 else 0
        return waypointIconCache.getOrPut(cacheKey) {
            val color = mapView.context.getThemeColor(
                if (locked) R.attr.gpxRouteLineColor else R.attr.colorPrimary
            )
            numberedCircleIcon(number, color)
        }
    }

    private fun numberedCircleIcon(number: Int, fillColor: Int): com.amap.api.maps.model.BitmapDescriptor {
        val size = DP(36f, mapView.context).pxValue
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = size / 2f
        val radius = size * 0.38f
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fillColor
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(mapView.context, R.color.white)
            strokeWidth = DP(2f, mapView.context).pxValue.toFloat()
            style = Paint.Style.STROKE
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(mapView.context, R.color.white)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = DP(if (number >= 10) 12f else 14f, mapView.context).pxValue.toFloat()
        }
        canvas.drawCircle(center, center, radius, fillPaint)
        canvas.drawCircle(center, center, radius, strokePaint)
        val textBaseline = center - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(number.toString(), center, textBaseline, textPaint)
        val descriptor = BitmapDescriptorFactory.fromBitmap(bitmap)
        bitmap.recycle()
        return descriptor
    }
}
