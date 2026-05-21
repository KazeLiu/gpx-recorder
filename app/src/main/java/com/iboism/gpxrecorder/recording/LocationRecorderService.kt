package com.iboism.gpxrecorder.recording

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
import androidx.core.app.NotificationCompat.PRIORITY_HIGH
import com.iboism.gpxrecorder.Events
import com.iboism.gpxrecorder.Keys
import com.iboism.gpxrecorder.MainActivity
import com.iboism.gpxrecorder.R
import com.iboism.gpxrecorder.model.GpxContent
import com.iboism.gpxrecorder.model.LastLocation
import com.iboism.gpxrecorder.model.RecordingConfiguration
import com.iboism.gpxrecorder.model.TrackPoint
import com.iboism.gpxrecorder.recording.location.AmapRecordingLocationProvider
import com.iboism.gpxrecorder.recording.location.GoogleRecordingLocationProvider
import com.iboism.gpxrecorder.recording.location.LocationProviderName
import com.iboism.gpxrecorder.recording.location.RecordingLocation
import com.iboism.gpxrecorder.recording.location.RecordingLocationProvider
import com.iboism.gpxrecorder.recording.location.RecordingLocationStatus
import com.iboism.gpxrecorder.settings.MapProvider
import com.iboism.gpxrecorder.settings.MapProviderPreference
import com.iboism.gpxrecorder.util.DateTimeFormatHelper
import io.realm.Realm
import org.greenrobot.eventbus.EventBus
import java.util.Date

/**
 * Created by Brad on 11/19/2017.
 */
class LocationRecorderService : Service() {
    private val NOTIFICATION_ID: Int = 180153
    private val TIMEOUT_NOTIFICATION_ID: Int = 180154
    var gpxId: Long? = null
    var isPaused: Boolean = false
    val recordingIntervalMillis: Long
        get() = config.interval
    val millisUntilNextTrackPoint: Long?
        get() {
            if (isPaused) return null
            val elapsed = SystemClock.elapsedRealtime() - lastTrackPointElapsedRealtimeMillis
            return (config.interval - elapsed).coerceAtLeast(0L)
        }

    private val serviceBinder = ServiceBinder()
    private var config = RecordingConfiguration()
    private var notificationHelper: RecordingNotification? = null
    private var lastTrackPointElapsedRealtimeMillis: Long = SystemClock.elapsedRealtime()
    private var locationProvider: RecordingLocationProvider? = null

    var locationStatus: RecordingLocationStatus = RecordingLocationStatus.waiting(LocationProviderName.Amap)
        private set

    private val notificationManager: NotificationManager
        get() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    override fun onTimeout(startId: Int, serviceType: Int) {
        val intentFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val openAppIntent = Intent(applicationContext, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(applicationContext, TIMEOUT_NOTIFICATION_ID, openAppIntent, intentFlags)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(getString(R.string.recording_stopped))
            .setContentText(getString(R.string.recording_timeout_description))
            .setContentIntent(openAppPendingIntent)
            .setPriority(PRIORITY_HIGH)
            .setForegroundServiceBehavior(FOREGROUND_SERVICE_IMMEDIATE)
            .setSmallIcon(R.drawable.ic_gpx_notification)
            .build()
        notificationManager.notify(TIMEOUT_NOTIFICATION_ID, notification)

        super.onTimeout(startId, serviceType)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        locationProvider?.destroy()
        locationProvider = null
        Realm.getDefaultConfiguration()?.let{ Realm.compactRealm(it) }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onBind(intent: Intent?): IBinder {
        return serviceBinder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when {
            intent?.extras?.containsKey(Keys.StopService) == true -> stopRecording()
            intent?.extras?.containsKey(Keys.PauseService) == true -> pauseRecording()
            intent?.extras?.containsKey(Keys.ResumeService) == true -> resumeRecording()
            intent?.extras?.containsKey(Keys.StartService) == true -> startRecording(intent)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun stopRecording() {
        EventBus.getDefault().apply {
            removeStickyEvent(Events.RecordingStartedEvent::class.java)
            post(Events.RecordingStoppedEvent(gpxId))
        }
        stopSelf()
    }

    fun pauseRecording() {
        val notificationHelper = notificationHelper ?: return
        val gpxId = gpxId ?: return
        isPaused = true
        EventBus.getDefault().post(Events.RecordingPausedEvent(gpxId))
        notificationManager.notify(NOTIFICATION_ID, notificationHelper.setPaused(true).notification())
        locationProvider?.stop()
    }

    @SuppressLint("MissingPermission")
    fun resumeRecording() {
        val notificationHelper = notificationHelper ?: return
        val gpxId = gpxId ?: return
        isPaused = false
        EventBus.getDefault().post(Events.RecordingResumedEvent(gpxId))
        val notification = notificationHelper.setPaused(false).notification()
        notificationManager.notify(NOTIFICATION_ID, notification)
        startLocationUpdates(notification)
    }

    @SuppressLint("MissingPermission")
    fun appendCurrentTrackPoint() {
        if (isPaused) return
        locationProvider?.requestCurrentLocation(
            onLocation = ::onLocationChanged,
            onStatusChanged = ::onLocationStatusChanged
        )
    }

    @SuppressLint("MissingPermission")
    fun updateRecordingInterval(intervalMillis: Long) {
        if (intervalMillis <= 0) return
        config = RecordingConfiguration(
            title = config.title,
            interval = intervalMillis
        )
        resetNextTrackPointCountdown()
        EventBus.getDefault().post(Events.RecordingIntervalUpdatedEvent(gpxId))

        if (isPaused) return
        appendCurrentTrackPoint()
        notificationHelper?.notification()?.let(::startLocationUpdates)
    }

    @SuppressLint("MissingPermission")
    private fun startRecording(intent: Intent?) {
        val gpxId = intent?.extras?.getLong(Keys.GpxId) ?: return
        val notificationHelper = RecordingNotification(applicationContext, gpxId)
        val notification = notificationHelper.notification()
        isPaused = false

        EventBus.getDefault().postSticky(Events.RecordingStartedEvent(gpxId))
        this.gpxId = gpxId
        this.notificationHelper = notificationHelper

        startForeground(NOTIFICATION_ID, notification)

        config = intent.extras?.getBundle(RecordingConfiguration.configKey)?.let {
            return@let RecordingConfiguration.fromBundle(it)
        } ?: RecordingConfiguration()
        resetNextTrackPointCountdown()

        selectLocationProvider()
        startLocationUpdates(notification)
    }

    private fun selectLocationProvider() {
        locationProvider?.destroy()
        locationProvider = when (MapProviderPreference.getProvider(applicationContext)) {
            MapProvider.Google -> GoogleRecordingLocationProvider(applicationContext)
            MapProvider.Amap -> AmapRecordingLocationProvider(applicationContext)
        }
        locationStatus = locationProvider?.status ?: RecordingLocationStatus.waiting(LocationProviderName.Amap)
    }

    private fun startLocationUpdates(notification: android.app.Notification) {
        if (locationProvider == null) selectLocationProvider()
        locationProvider?.start(
            config = config,
            notificationId = NOTIFICATION_ID,
            notification = notification,
            onLocation = ::onLocationChanged,
            onStatusChanged = ::onLocationStatusChanged
        )
    }

    private fun onLocationStatusChanged(status: RecordingLocationStatus) {
        locationStatus = status
        EventBus.getDefault().post(Events.RecordingLocationStatusUpdatedEvent(gpxId))
    }

    private fun onLocationChanged(location: RecordingLocation?) {
        location?.let { loc ->
            val accuracy = loc.status.accuracyMeters
            if (accuracy != null && accuracy > 40) return
            LastLocation.put(lat = loc.lat, lon = loc.lon)
            val realm = Realm.getDefaultInstance()
            realm.executeTransaction { r ->
                val point = TrackPoint(
                    lat = loc.lat,
                    lon = loc.lon,
                    ele = loc.ele,
                    time = DateTimeFormatHelper.formatDate(loc.time)
                )
                r.copyToRealm(point)
                GpxContent.withId(gpxId, r)?.let { gpx ->
                    gpx.trackList.last()?.segments?.last()?.addPoint(point)
                }
            }
            realm.close()
            resetNextTrackPointCountdown()
            EventBus.getDefault().post(Events.RecordingTrackPointAddedEvent(gpxId))
        }
    }

    private fun resetNextTrackPointCountdown() {
        lastTrackPointElapsedRealtimeMillis = SystemClock.elapsedRealtime()
    }

    inner class ServiceBinder : Binder() {
        fun getService() = this@LocationRecorderService
    }

    companion object {
        fun createStopRecordingIntent(context: Context): Intent {
            return Intent(context, LocationRecorderService::class.java).putExtra(Keys.StopService, true)
        }

        fun createPauseRecordingIntent(context: Context): Intent {
            return Intent(context, LocationRecorderService::class.java).putExtra(Keys.PauseService, true)
        }

        fun createResumeRecordingIntent(context: Context): Intent {
            return Intent(context, LocationRecorderService::class.java).putExtra(Keys.ResumeService, true)
        }

        fun requestStopRecording(context: Context) {
            context.startService(createStopRecordingIntent(context))
        }

        fun requestPauseRecording(context: Context) {
            context.startService(createPauseRecordingIntent(context))
        }

        fun requestResumeRecording(context: Context) {
            context.startService(createResumeRecordingIntent(context))
        }

        fun requestStartRecording(context: Context, gpxId: Long, configBundle: Bundle) {
            val intent = Intent(context, LocationRecorderService::class.java)
            intent.putExtra(Keys.StartService, true)
            intent.putExtra(Keys.GpxId, gpxId)
            intent.putExtra(RecordingConfiguration.configKey, configBundle)
            context.startForegroundService(intent)
        }
    }
}

