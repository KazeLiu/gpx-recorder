package com.iboism.gpxrecorder.settings

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatDelegate
import com.iboism.gpxrecorder.R
import com.iboism.gpxrecorder.util.Prefs

object ThemePreference {
    private const val darkModeKey = "kDarkModeEnabled"
    private const val followSystemKey = "kDarkModeFollowSystemEnabled"
    private const val colorThemeKey = "kColorTheme"

    enum class ColorTheme(
        val value: String,
        val labelRes: Int,
        @param:StyleRes val activityThemeRes: Int,
    ) {
        Pink("pink", R.string.color_theme_pink, R.style.AppTheme_NoActionBar),
        Blue("blue", R.string.color_theme_blue, R.style.AppTheme_Blue_NoActionBar);

        companion object {
            fun fromValue(value: String?): ColorTheme {
                return entries.firstOrNull { it.value == value } ?: Blue
            }
        }
    }

    fun isDarkModeEnabled(context: Context): Boolean {
        val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightMode == Configuration.UI_MODE_NIGHT_YES
    }

    fun isFollowSystemEnabled(context: Context): Boolean {
        return Prefs.getDefault(context).getBoolean(followSystemKey, true)
    }

    fun setFollowSystemEnabled(context: Context, isEnabled: Boolean) {
        Prefs.getDefault(context)
            .edit()
            .putBoolean(followSystemKey, isEnabled)
            .apply()

        applyStored(context)
    }

    fun isManualDarkModeEnabled(context: Context): Boolean {
        return Prefs.getDefault(context).getBoolean(darkModeKey, false)
    }

    fun setManualDarkModeEnabled(context: Context, isEnabled: Boolean) {
        Prefs.getDefault(context)
            .edit()
            .putBoolean(darkModeKey, isEnabled)
            .apply()

        applyStored(context)
    }

    fun getColorTheme(context: Context): ColorTheme {
        return ColorTheme.fromValue(Prefs.getDefault(context).getString(colorThemeKey, null))
    }

    fun setColorTheme(context: Context, theme: ColorTheme) {
        Prefs.getDefault(context)
            .edit()
            .putString(colorThemeKey, theme.value)
            .apply()
    }

    @StyleRes
    fun getActivityThemeRes(context: Context): Int {
        return getColorTheme(context).activityThemeRes
    }

    fun applyStored(context: Context) {
        val mode = if (isFollowSystemEnabled(context)) {
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        } else if (isManualDarkModeEnabled(context)) {
            AppCompatDelegate.MODE_NIGHT_YES
        } else {
            AppCompatDelegate.MODE_NIGHT_NO
        }
        apply(mode)
    }

    private fun apply(mode: Int) {
        if (AppCompatDelegate.getDefaultNightMode() == mode) return
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
