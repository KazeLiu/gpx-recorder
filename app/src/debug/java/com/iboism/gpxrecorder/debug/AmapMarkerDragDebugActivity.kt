package com.iboism.gpxrecorder.debug

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.BitmapDescriptor
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.iboism.gpxrecorder.R
import com.iboism.gpxrecorder.records.details.bitmapFromVector
import kotlin.math.roundToInt

class AmapMarkerDragDebugActivity : AppCompatActivity() {
    private lateinit var mapView: MapView
    private var map: AMap? = null
    private var latestTouch: TouchSnapshot? = null
    private var dragEventCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        MapsInitializer.updatePrivacyShow(this, true, true)
        MapsInitializer.updatePrivacyAgree(this, true)
        MapsInitializer.setApiKey(getString(R.string.amap_api_key))
        MapsInitializer.loadWorldVectorMap(true)

        super.onCreate(savedInstanceState)
        mapView = MapView(this)
        mapView.onCreate(savedInstanceState)
        setContentView(createContentView())
        setupMap()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        mapView.onDestroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    private fun createContentView(): FrameLayout {
        return FrameLayout(this).apply {
            addView(
                mapView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                TextView(context).apply {
                    setTextColor(Color.WHITE)
                    setBackgroundColor(0x99000000.toInt())
                    textSize = 13f
                    setPadding(24, 18, 24, 18)
                    text = "AMap drag diagnostics\nDrag markers: CENTER, FLAT, BOTTOM\nTell Codex when done."
                },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    setMargins(16, 48, 16, 16)
                }
            )
        }
    }

    private fun setupMap() {
        val debugMap = mapView.map
        map = debugMap
        debugMap.uiSettings.isZoomControlsEnabled = true
        debugMap.uiSettings.isCompassEnabled = true
        debugMap.setOnMapLoadedListener {
            logBitmapDiagnostics(R.drawable.ic_draggable_track_point, "draggable")
            logBitmapDiagnostics(R.drawable.ic_insert_track_point, "insert")
            addDebugMarkers(debugMap)
            debugMap.moveCamera(CameraUpdateFactory.newLatLngZoom(CENTER, 16f))
            logMapStatus("MAP_LOADED")
        }
        debugMap.setOnMapTouchListener { event -> onMapTouch(event) }
        debugMap.setOnMarkerDragListener(object : AMap.OnMarkerDragListener {
            override fun onMarkerDragStart(marker: Marker) {
                dragEventCount = 0
                logMarker("DRAG_START", marker)
            }

            override fun onMarkerDrag(marker: Marker) {
                dragEventCount += 1
                logMarker("DRAG_MOVE#$dragEventCount", marker)
            }

            override fun onMarkerDragEnd(marker: Marker) {
                logMarker("DRAG_END", marker)
            }
        })
    }

    private fun addDebugMarkers(debugMap: AMap) {
        debugMap.addDiagnosticMarker(
            label = "CENTER flat=false anchor=.5,.5",
            position = LatLng(CENTER.latitude, CENTER.longitude - 0.0020),
            flat = false,
            anchorU = .5f,
            anchorV = .5f
        )
        debugMap.addDiagnosticMarker(
            label = "FLAT flat=true anchor=.5,.5",
            position = CENTER,
            flat = true,
            anchorU = .5f,
            anchorV = .5f
        )
        debugMap.addDiagnosticMarker(
            label = "BOTTOM flat=false anchor=.5,1",
            position = LatLng(CENTER.latitude, CENTER.longitude + 0.0020),
            flat = false,
            anchorU = .5f,
            anchorV = 1f
        )
    }

    private fun AMap.addDiagnosticMarker(
        label: String,
        position: LatLng,
        flat: Boolean,
        anchorU: Float,
        anchorV: Float
    ) {
        val marker = addMarker(
            MarkerOptions()
                .position(position)
                .setFlat(flat)
                .title(label)
                .snippet("drag me")
                .icon(getBitmapDescriptor(R.drawable.ic_draggable_track_point))
                .draggable(true)
                .anchor(anchorU, anchorV)
        )
        marker.setObject(label)
        logMarker("MARKER_ADDED", marker)
    }

    private fun onMapTouch(event: MotionEvent) {
        latestTouch = TouchSnapshot(
            action = event.actionMasked,
            x = event.x,
            y = event.y,
            rawX = event.rawX,
            rawY = event.rawY,
            pointerCount = event.pointerCount,
            eventTime = event.eventTime
        )
        Log.d(
            TAG,
            "TOUCH action=${event.actionName()} x=${event.x.fmt()} y=${event.y.fmt()} " +
                "rawX=${event.rawX.fmt()} rawY=${event.rawY.fmt()} pointers=${event.pointerCount} " +
                "time=${event.eventTime}"
        )
    }

    private fun logMarker(stage: String, marker: Marker) {
        val projection = map?.projection
        val screenPoint = projection?.toScreenLocation(marker.position)
        val touch = latestTouch
        val dx = if (screenPoint != null && touch != null) screenPoint.x - touch.x else null
        val dy = if (screenPoint != null && touch != null) screenPoint.y - touch.y else null
        Log.d(
            TAG,
            "$stage label=${marker.getObject()} id=${marker.id} " +
                "flat=${marker.isFlat} viewMode=${marker.isViewMode} draggable=${marker.isDraggable} " +
                "anchor=(${marker.anchorU.fmt()},${marker.anchorV.fmt()}) " +
                "lat=${marker.position.latitude.fmt6()} lon=${marker.position.longitude.fmt6()} " +
                "screen=${screenPoint.format()} latestTouch=${touch.format()} " +
                "screenMinusTouch=(${dx.fmt()},${dy.fmt()})"
        )
    }

    private fun logMapStatus(stage: String) {
        val status = map?.cameraPosition
        Log.d(
            TAG,
            "$stage camera target=${status?.target?.latitude?.fmt6()},${status?.target?.longitude?.fmt6()} " +
                "zoom=${status?.zoom?.fmt()} tilt=${status?.tilt?.fmt()} bearing=${status?.bearing?.fmt()}"
        )
    }

    private fun logBitmapDiagnostics(@DrawableRes drawableId: Int, name: String) {
        val bitmap = bitmapFromVector(this, drawableId)
        val bounds = bitmap.alphaBounds()
        Log.d(
            TAG,
            "BITMAP name=$name width=${bitmap.width} height=${bitmap.height} " +
                "alphaBounds=${bounds.format()} center=(${(bitmap.width / 2f).fmt()},${(bitmap.height / 2f).fmt()})"
        )
        bitmap.recycle()
    }

    private fun getBitmapDescriptor(@DrawableRes id: Int): BitmapDescriptor {
        val bitmap = bitmapFromVector(this, id)
        val descriptor = BitmapDescriptorFactory.fromBitmap(bitmap)
        bitmap.recycle()
        return descriptor
    }

    private fun MotionEvent.actionName(): String {
        return when (actionMasked) {
            MotionEvent.ACTION_DOWN -> "DOWN"
            MotionEvent.ACTION_UP -> "UP"
            MotionEvent.ACTION_MOVE -> "MOVE"
            MotionEvent.ACTION_CANCEL -> "CANCEL"
            MotionEvent.ACTION_POINTER_DOWN -> "POINTER_DOWN"
            MotionEvent.ACTION_POINTER_UP -> "POINTER_UP"
            else -> actionMasked.toString()
        }
    }

    private fun Bitmap.alphaBounds(): AlphaBounds {
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                val alpha = getPixel(x, y) ushr 24
                if (alpha > 0) {
                    if (x < left) left = x
                    if (y < top) top = y
                    if (x > right) right = x
                    if (y > bottom) bottom = y
                }
            }
        }
        return AlphaBounds(left, top, right, bottom)
    }

    private fun Point?.format(): String {
        return if (this == null) "null" else "(${x},${y})"
    }

    private fun TouchSnapshot?.format(): String {
        return if (this == null) {
            "null"
        } else {
            "${actionName()}(${x.fmt()},${y.fmt()}) raw=(${rawX.fmt()},${rawY.fmt()}) pointers=$pointerCount t=$eventTime"
        }
    }

    private fun TouchSnapshot.actionName(): String {
        return when (action) {
            MotionEvent.ACTION_DOWN -> "DOWN"
            MotionEvent.ACTION_UP -> "UP"
            MotionEvent.ACTION_MOVE -> "MOVE"
            MotionEvent.ACTION_CANCEL -> "CANCEL"
            MotionEvent.ACTION_POINTER_DOWN -> "POINTER_DOWN"
            MotionEvent.ACTION_POINTER_UP -> "POINTER_UP"
            else -> action.toString()
        }
    }

    private fun AlphaBounds.format(): String {
        return if (right < left || bottom < top) {
            "empty"
        } else {
            "left=$left top=$top right=$right bottom=$bottom width=${right - left + 1} height=${bottom - top + 1}"
        }
    }

    private fun Float?.fmt(): String {
        return this?.let { "%.2f".format(it) } ?: "null"
    }

    private fun Double?.fmt6(): String {
        return this?.let { "%.6f".format(it) } ?: "null"
    }

    private data class TouchSnapshot(
        val action: Int,
        val x: Float,
        val y: Float,
        val rawX: Float,
        val rawY: Float,
        val pointerCount: Int,
        val eventTime: Long
    )

    private data class AlphaBounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    private companion object {
        private const val TAG = "AmapDragDiag"
        private val CENTER = LatLng(39.908823, 116.397470)
    }
}
