package com.iboism.gpxrecorder.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.iboism.gpxrecorder.util.Prefs

object LocalePreference {
    private const val languageTagKey = "kLanguageTag"

    fun getLanguageTag(context: Context): String {
        return Prefs.getDefault(context).getString(languageTagKey, "") ?: ""
    }

    fun setLanguageTag(context: Context, languageTag: String) {
        Prefs.getDefault(context)
            .edit()
            .putString(languageTagKey, languageTag)
            .apply()

        apply(languageTag)
    }

    fun applyStored(context: Context) {
        apply(getLanguageTag(context))
    }

    private fun apply(languageTag: String) {
        if (AppCompatDelegate.getApplicationLocales().toLanguageTags() == languageTag) return
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag))
    }
}
