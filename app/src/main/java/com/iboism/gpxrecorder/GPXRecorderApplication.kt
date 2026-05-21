package com.iboism.gpxrecorder

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.MapsInitializer
import com.getkeepsafe.relinker.MissingLibraryException
import com.iboism.gpxrecorder.extensions.setRealmInitFailure
import com.iboism.gpxrecorder.model.Schema
import com.iboism.gpxrecorder.recording.CHANNEL_ID
import com.iboism.gpxrecorder.settings.ThemePreference
import io.realm.Realm
import io.realm.exceptions.RealmException


/**
 * Created by Brad on 11/18/2017.
 */
class GPXRecorderApplication: Application() {

    override fun onCreate() {
        super.onCreate()
        ThemePreference.applyStored(this)
        initializeAmap()

        try {
            initializeRealm()
            applicationContext.setRealmInitFailure(false)
        } catch (e: RealmException) {
            applicationContext.setRealmInitFailure(true)
        } catch (e: MissingLibraryException) {
            applicationContext.setRealmInitFailure(true)
        }

        createNotificationChannel()
    }

    private fun initializeAmap() {
        val apiKey = getString(R.string.amap_api_key)
        MapsInitializer.updatePrivacyShow(applicationContext, true, true)
        MapsInitializer.updatePrivacyAgree(applicationContext, true)
        MapsInitializer.setApiKey(apiKey)
        AMapLocationClient.updatePrivacyShow(applicationContext, true, true)
        AMapLocationClient.updatePrivacyAgree(applicationContext, true)
        AMapLocationClient.setApiKey(apiKey)
    }

    private fun initializeRealm() {
        Realm.init(applicationContext)
        Realm.setDefaultConfiguration(Schema.configuration())
    }

    private fun createNotificationChannel() {
        val name = getString(R.string.channel_name)
        val description = getString(R.string.channel_description)
        val importance = NotificationManager.IMPORTANCE_DEFAULT
        val channel = NotificationChannel(CHANNEL_ID, name, importance)
        channel.description = description
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.createNotificationChannel(channel)
    }
}
