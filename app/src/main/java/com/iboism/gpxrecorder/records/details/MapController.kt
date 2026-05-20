package com.iboism.gpxrecorder.records.details

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.widget.FrameLayout
import androidx.annotation.DrawableRes
import androidx.core.content.res.ResourcesCompat
import com.iboism.gpxrecorder.model.GpxContent
import com.iboism.gpxrecorder.settings.MapProvider
import com.iboism.gpxrecorder.settings.MapProviderPreference
import io.realm.Realm

class MapController(private val mapContainer: FrameLayout, private val gpxId: Long) {
    private val delegate: RouteMapController =
        when (MapProviderPreference.getProvider(mapContainer.context)) {
            MapProvider.Google -> GoogleRouteMapController(mapContainer, gpxId)
            MapProvider.Amap -> AmapRouteMapController(mapContainer, gpxId)
        }

    var shouldDrawEnd: Boolean
        get() = delegate.shouldDrawEnd
        set(value) {
            delegate.shouldDrawEnd = value
        }

    fun onCreate(savedInstanceState: Bundle?) = delegate.onCreate(savedInstanceState)
    fun onStart() = delegate.onStart()
    fun onResume() = delegate.onResume()
    fun onPause() = delegate.onPause()
    fun onDestroy() = delegate.onDestroy()
    fun onLowMemory() = delegate.onLowMemory()
    fun onSaveInstanceState(outState: Bundle) = delegate.onSaveInstanceState(outState)
    fun redraw() = delegate.redraw()
    fun toggleMapType() = delegate.toggleMapType()
}

internal interface RouteMapController {
    var shouldDrawEnd: Boolean

    fun onCreate(savedInstanceState: Bundle?)
    fun onStart()
    fun onResume()
    fun onPause()
    fun onDestroy()
    fun onLowMemory()
    fun onSaveInstanceState(outState: Bundle)
    fun redraw()
    fun toggleMapType()
}

internal fun loadGpxContent(gpxId: Long): GpxContent? {
    val realm = Realm.getDefaultInstance()
    return try {
        GpxContent.withId(gpxId, realm)?.let { realm.copyFromRealm(it) }
    } finally {
        realm.close()
    }
}

internal fun bitmapFromVector(context: Context, @DrawableRes id: Int): Bitmap {
    val vectorDrawable = ResourcesCompat.getDrawable(context.resources, id, null)
    val bitmap = Bitmap.createBitmap(
        vectorDrawable!!.intrinsicWidth,
        vectorDrawable.intrinsicHeight,
        Bitmap.Config.ARGB_8888
    )
    val canvas = Canvas(bitmap)
    vectorDrawable.setBounds(0, 0, canvas.width, canvas.height)
    vectorDrawable.draw(canvas)
    return bitmap
}
