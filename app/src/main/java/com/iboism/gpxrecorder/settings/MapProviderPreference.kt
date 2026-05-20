package com.iboism.gpxrecorder.settings

import android.content.Context
import com.iboism.gpxrecorder.util.Prefs

enum class MapProvider(val value: String) {
    Google("google"),
    Amap("amap");

    companion object {
        fun fromValue(value: String?): MapProvider {
            return entries.firstOrNull { it.value == value } ?: Google
        }
    }
}

object MapProviderPreference {
    private const val mapProviderKey = "kMapProvider"

    fun getProvider(context: Context): MapProvider {
        return MapProvider.fromValue(Prefs.getDefault(context).getString(mapProviderKey, MapProvider.Google.value))
    }

    fun setProvider(context: Context, provider: MapProvider) {
        Prefs.getDefault(context)
            .edit()
            .putString(mapProviderKey, provider.value)
            .apply()
    }
}
