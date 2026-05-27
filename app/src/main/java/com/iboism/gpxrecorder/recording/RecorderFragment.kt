package com.iboism.gpxrecorder.recording

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iboism.gpxrecorder.Events
import com.iboism.gpxrecorder.Keys
import com.iboism.gpxrecorder.R
import com.iboism.gpxrecorder.databinding.FragmentActiveRouteDetailsBinding
import com.iboism.gpxrecorder.model.GpxContent
import com.iboism.gpxrecorder.recording.configurator.RecordingConfiguratorView
import com.iboism.gpxrecorder.records.details.MapController
import com.iboism.gpxrecorder.util.DateTimeFormatHelper
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.realm.Realm
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class RecorderFragment : Fragment(), RecorderServiceConnection.OnServiceConnectedDelegate {
    private var gpxId: Long? = null
    private var mapController: MapController? = null
    private var serviceConnection: RecorderServiceConnection = RecorderServiceConnection(this)
    private var observableInterval: Observable<Long> = Observable.interval(1, TimeUnit.SECONDS).observeOn(AndroidSchedulers.mainThread())
    private var intervalObserver: Disposable? = null
    private lateinit var binding: FragmentActiveRouteDetailsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gpxId = arguments?.getLong(Keys.GpxId)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        binding = FragmentActiveRouteDetailsBinding.inflate(layoutInflater, container, false)
        val headerInitialPaddingTop = binding.currentRecHeader.paddingTop
        val moreInitialPaddingTop = binding.moreBtn.paddingTop

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val topClearance = systemBars.top + resources.getDimensionPixelSize(R.dimen.status_bar_content_extra_padding)
            binding.currentRecHeader.setPadding(
                binding.currentRecHeader.paddingLeft,
                headerInitialPaddingTop + topClearance,
                binding.currentRecHeader.paddingRight,
                binding.currentRecHeader.paddingBottom
            )
            binding.moreBtn.setPadding(
                binding.moreBtn.paddingLeft,
                moreInitialPaddingTop + topClearance,
                binding.moreBtn.paddingRight,
                binding.moreBtn.paddingBottom
            )
            view.setPadding(0, 0, 0, systemBars.bottom)
            insets
        }

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val gpxId = gpxId ?: return

        binding.addWptBtn.setOnClickListener(this::appendTrackPointButtonClicked)
        binding.playpauseBtn.setOnClickListener(this::playPauseButtonClicked)
        binding.stopBtn.setOnClickListener(this::stopButtonClicked)
        binding.trackPointCountTv.setOnClickListener(this::trackPointCountClicked)
        binding.locationStatusChip.setOnClickListener { locationStatusClicked() }

        binding.moreBtn.setOnClickListener { showMoreMenu() }

        updateUI(gpxId)

        binding.mapView.let {
            val controller = MapController(it, gpxId)
            controller.shouldDrawEnd = false
            controller.shouldCenterOnLoad = false
            controller.shouldCenterOnCurrentLocationOnLoad = true
            controller.showCurrentLocationButton = true
            mapController = controller
            controller.onCreate(savedInstanceState)
        }
    }

    private fun appendTrackPointButtonClicked(view: View) {
        serviceConnection.service?.appendCurrentTrackPoint()
    }

    private fun playPauseButtonClicked(view: View) {
        serviceConnection.service?.let {
            if (it.isPaused) {
                it.resumeRecording()
            } else {
                it.pauseRecording()
            }
        }
    }

    private fun stopButtonClicked(view: View) {
        val context = context ?: return
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.stop_recording_confirm_title)
            .setMessage(R.string.stop_recording_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.stop_recording) { _, _ ->
                LocationRecorderService.requestStopRecording(context)
            }
            .show()
    }

    private fun showMoreMenu() {
        val context = context ?: return
        val items = arrayOf(
            getString(R.string.toggle_map_type),
            getString(R.string.update_recording_interval)
        )

        MaterialAlertDialogBuilder(context)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> mapController?.toggleMapType()
                    1 -> showUpdateIntervalDialog()
                }
            }
            .show()
    }

    private fun trackPointCountClicked(view: View) {
        val context = context ?: return
        val gpxId = gpxId ?: return
        val realm = Realm.getDefaultInstance()
        val gpxContent = GpxContent.withId(gpxId, realm)
        if (gpxContent == null) {
            realm.close()
            return
        }
        val startTimeText = DateTimeFormatHelper.toReadableString24Hour(gpxContent.date)
        val startDate = DateTimeFormatHelper.parseDate(gpxContent.date)
        realm.close()

        val durationText = startDate?.let {
            formatDurationShort(Date().time - it.time)
        } ?: getString(R.string.unknown_recording_duration)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.recording_info_title)
            .setMessage(getString(R.string.recording_info_message, durationText, startTimeText))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showUpdateIntervalDialog() {
        val context = context ?: return
        val dialogView = LayoutInflater.from(context).inflate(R.layout.config_dialog, null)
        dialogView.findViewById<View>(R.id.config_title_editText).visibility = View.GONE
        dialogView.findViewById<View>(R.id.config_title_input_layout).visibility = View.GONE

        val configuratorView = RecordingConfiguratorView(
            dialogView,
            serviceConnection.service?.recordingIntervalMillis ?: return,
            isTitleEditable = false,
            screenTitleTextRes = R.string.update_recording_interval,
            doneButtonTextRes = R.string.update_recording_interval
        )
        configuratorView.doneButton.visibility = View.GONE

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(dialogView)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.update_recording_interval, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                serviceConnection.service?.updateRecordingInterval(configuratorView.getIntervalMillis())
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        EventBus.getDefault().register(this)
    }

    override fun onDetach() {
        super.onDetach()
        EventBus.getDefault().unregister(this)
    }

    override fun onResume() {
        super.onResume()
        mapController?.onResume()

        intervalObserver = observableInterval.subscribe {
            updateUI(gpxId, shouldRedrawMap = false)
        }
    }

    override fun onDestroyView() {
        mapController?.onDestroy()
        super.onDestroyView()
    }

    override fun onPause() {
        super.onPause()
        mapController?.onPause()
        intervalObserver?.dispose()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapController?.onLowMemory()
    }

    override fun onStart() {
        super.onStart()
        mapController?.onStart()
        requestServiceConnectionIfNeeded()
    }

    override fun onStop() {
        context?.let { serviceConnection.disconnect(it, notifyDelegate = false) }
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        mapController?.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    private fun updateUI(gpxIdOrNull: Long?, shouldRedrawMap: Boolean = true) {
        //val distance = content.trackList.first()?.segments?.first()?.distance ?: 0f todo: add distance to ui
        val gpxId = gpxIdOrNull ?: return
        val realm = Realm.getDefaultInstance()
        val gpxContent = GpxContent.withId(gpxId, realm)
        if (gpxContent == null) {
            realm.close()
            return
        }
        val routeTitle = gpxContent.title
        val trackPointCount = gpxContent.trackPointCount()
        realm.close()

        var isPaused = false
        serviceConnection.service?.let {
            isPaused = it.isPaused
        }

        val recordingStatusParts = mutableListOf(
            getString(R.string.recording_in_progress)
        )
        serviceConnection.service?.let { service ->
            recordingStatusParts.add(
                getString(R.string.recording_interval_short, formatDurationShort(service.recordingIntervalMillis))
            )
            recordingStatusParts.add(
                service.millisUntilNextTrackPoint?.let { remainingMillis ->
                    getString(R.string.next_track_point_short, formatDurationShort(remainingMillis))
                } ?: getString(R.string.next_track_point_paused_short)
            )
        }
        binding.currentRecHeader.text = recordingStatusParts.joinToString(" · ")
        binding.locationStatusChip.text = serviceConnection.service
            ?.locationStatus
            ?.displayText(requireContext())
            ?: getString(R.string.location_status_waiting)
        binding.locationStatusChip.contentDescription = binding.locationStatusChip.text
        binding.trackPointCountTv.text = resources.getQuantityString(
            R.plurals.point_count,
            trackPointCount,
            trackPointCount
        )
        binding.routeTitleTv.text = routeTitle
        val pauseResumeString = if (isPaused) R.string.resume_recording else R.string.pause_recording
        binding.playpauseBtn.setText(pauseResumeString)
        binding.playpauseBtn.setIconResource(if (isPaused) R.drawable.ic_play else R.drawable.ic_pause)
        updateRecordingHeaderStyle(isPaused)
        if (shouldRedrawMap) {
            mapController?.redraw()
        }
    }

    private fun locationStatusClicked() {
        val context = context ?: return
        val status = serviceConnection.service?.locationStatus ?: return
        val errorMessage = status.errorMessage ?: return

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.error)
            .setMessage(
                getString(
                    R.string.location_status_error,
                    status.provider.label(context),
                    errorMessage
                )
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun updateRecordingHeaderStyle(isPaused: Boolean) {
        val headerBackgroundColor = ContextCompat.getColor(
            requireContext(),
            if (isPaused) R.color.md_errorContainer else R.color.nav_bar_surface
        )
        val headerContentColor = ContextCompat.getColor(
            requireContext(),
            if (isPaused) R.color.md_onErrorContainer else R.color.on_nav_bar_surface
        )

        binding.recordingHeaderBackground.setBackgroundColor(headerBackgroundColor)
        binding.currentRecHeader.setTextColor(headerContentColor)
        binding.routeTitleTv.setTextColor(headerContentColor)
        binding.moreBtn.setColorFilter(headerContentColor)
    }

    private fun formatDurationShort(durationMillis: Long): String {
        val totalSeconds = (durationMillis / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L

        return buildString {
            if (hours > 0) append(String.format(Locale.ROOT, "%d%s", hours, getString(R.string.duration_hours_short)))
            if (minutes > 0 || hours > 0) {
                append(String.format(Locale.ROOT, "%d%s", minutes, getString(R.string.duration_minutes_short)))
            }
            if (seconds > 0 || isEmpty()) {
                append(String.format(Locale.ROOT, "%d%s", seconds, getString(R.string.duration_seconds_short)))
            }
        }
    }

    override fun onServiceConnected(serviceConnection: RecorderServiceConnection) {
        updateUI(gpxId)
    }

    override fun onServiceDisconnected() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                dismiss()
            }
        }
    }

    @Subscribe(sticky = true)
    fun onServiceStartedEvent(event: Events.RecordingStartedEvent) {
        requestServiceConnectionIfNeeded()
    }

    @Subscribe
    fun onServiceStoppedEvent(event: Events.RecordingStoppedEvent) {
        serviceConnection.service = null
    }

    @Subscribe
    fun onServicePausedEvent(event: Events.RecordingPausedEvent) {
        updateUI(gpxId)
    }

    @Subscribe
    fun onServiceResumedEvent(event: Events.RecordingResumedEvent) {
        updateUI(gpxId)
    }

    @Subscribe
    fun onTrackPointAddedEvent(event: Events.RecordingTrackPointAddedEvent) {
        updateUI(gpxId)
    }

    @Subscribe
    fun onRecordingIntervalUpdatedEvent(event: Events.RecordingIntervalUpdatedEvent) {
        updateUI(gpxId)
    }

    @Subscribe
    fun onRecordingLocationStatusUpdatedEvent(event: Events.RecordingLocationStatusUpdatedEvent) {
        updateUI(gpxId, shouldRedrawMap = false)
    }

    private fun requestServiceConnectionIfNeeded() {
        if (serviceConnection.service == null) {
            serviceConnection.requestConnection(requireContext())
        } else {
            updateUI(serviceConnection.service?.gpxId)
        }
    }

    private fun dismiss() {
        parentFragmentManager.popBackStack()
    }

    companion object {
        fun newInstance(gpxId: Long): RecorderFragment {
            val args = Bundle()
            args.putLong(Keys.GpxId, gpxId)

            val fragment = RecorderFragment()
            fragment.arguments = args

            return fragment
        }
    }
}
