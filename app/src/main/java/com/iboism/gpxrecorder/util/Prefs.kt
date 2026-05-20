package com.iboism.gpxrecorder.util

import android.content.Context
import android.content.SharedPreferences
import com.iboism.gpxrecorder.BuildConfig

class Prefs {
    companion object {
        fun getDefault(context: Context): SharedPreferences {
            return context.getSharedPreferences(BuildConfig.APPLICATION_ID, Context.MODE_PRIVATE)
        }
    }
}
