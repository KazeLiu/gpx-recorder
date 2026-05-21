package com.iboism.gpxrecorder.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.iboism.gpxrecorder.util.Prefs

object ThemePreference {
    private const val darkModeKey = "kDarkModeEnabled"

    fun isDarkModeEnabled(context: Context): Boolean {
        return Prefs.getDefault(context).getBoolean(darkModeKey, false)
    }

    fun setDarkModeEnabled(context: Context, isEnabled: Boolean) {
        Prefs.getDefault(context)
            .edit()
            .putBoolean(darkModeKey, isEnabled)
            .apply()

        apply(isEnabled)
    }

    fun applyStored(context: Context) {
        apply(isDarkModeEnabled(context))
    }

    private fun apply(isEnabled: Boolean) {
        val mode = if (isEnabled) {
            AppCompatDelegate.MODE_NIGHT_YES
        } else {
            AppCompatDelegate.MODE_NIGHT_NO
        }
        if (AppCompatDelegate.getDefaultNightMode() == mode) return
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
