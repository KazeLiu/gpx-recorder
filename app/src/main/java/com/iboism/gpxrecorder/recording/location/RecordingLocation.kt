package com.iboism.gpxrecorder.recording.location

import android.content.Context
import com.iboism.gpxrecorder.R
import java.util.Date
import kotlin.math.roundToInt

data class RecordingLocation(
    val lat: Double,
    val lon: Double,
    val ele: Double?,
    val time: Date,
    val status: RecordingLocationStatus
)

data class RecordingLocationStatus(
    val provider: LocationProviderName,
    val method: LocationMethod,
    val accuracyMeters: Float? = null,
    val satellites: Int? = null,
    val signalStrength: LocationSignalStrength = LocationSignalStrength.Unknown,
    val errorMessage: String? = null
) {
    fun displayText(context: Context): String {
        if (errorMessage != null) {
            return context.getString(
                R.string.location_status_error,
                provider.label(context),
                errorMessage
            )
        }

        val parts = mutableListOf(
            provider.label(context),
            method.label(context)
        )
        accuracyMeters?.let {
            parts.add(context.getString(R.string.location_status_accuracy, it.roundToInt()))
        }
        satellites?.takeIf { it > 0 }?.let {
            parts.add(context.resources.getQuantityString(R.plurals.satellite_count, it, it))
        }
        parts.add(signalStrength.label(context))
        return parts.joinToString(" · ")
    }

    companion object {
        fun waiting(provider: LocationProviderName): RecordingLocationStatus {
            return RecordingLocationStatus(
                provider = provider,
                method = LocationMethod.Waiting,
                signalStrength = LocationSignalStrength.Unknown
            )
        }
    }
}

enum class LocationProviderName {
    Google,
    Amap;

    fun label(context: Context): String {
        return when (this) {
            Google -> context.getString(R.string.location_provider_google)
            Amap -> context.getString(R.string.location_provider_amap)
        }
    }
}

enum class LocationMethod {
    Waiting,
    Fused,
    Gps,
    Wifi,
    Cell,
    Network,
    Cache,
    AmapNetwork,
    Unknown;

    fun label(context: Context): String {
        return when (this) {
            Waiting -> context.getString(R.string.location_method_waiting)
            Fused -> context.getString(R.string.location_method_fused)
            Gps -> context.getString(R.string.location_method_gps)
            Wifi -> context.getString(R.string.location_method_wifi)
            Cell -> context.getString(R.string.location_method_cell)
            Network -> context.getString(R.string.location_method_network)
            Cache -> context.getString(R.string.location_method_cache)
            AmapNetwork -> context.getString(R.string.location_method_amap_network)
            Unknown -> context.getString(R.string.location_method_unknown)
        }
    }
}

enum class LocationSignalStrength {
    Strong,
    Medium,
    Weak,
    Unknown;

    fun label(context: Context): String {
        return when (this) {
            Strong -> context.getString(R.string.location_signal_strong)
            Medium -> context.getString(R.string.location_signal_medium)
            Weak -> context.getString(R.string.location_signal_weak)
            Unknown -> context.getString(R.string.location_signal_unknown)
        }
    }
}

internal fun signalStrengthFromAccuracy(accuracyMeters: Float?): LocationSignalStrength {
    val accuracy = accuracyMeters ?: return LocationSignalStrength.Unknown
    return when {
        accuracy <= 20f -> LocationSignalStrength.Strong
        accuracy <= 60f -> LocationSignalStrength.Medium
        else -> LocationSignalStrength.Weak
    }
}
