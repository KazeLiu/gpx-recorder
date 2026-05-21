package com.iboism.gpxrecorder.recording

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.iboism.gpxrecorder.Events
import com.iboism.gpxrecorder.Keys
import com.iboism.gpxrecorder.R
import com.iboism.gpxrecorder.databinding.FragmentActiveRouteDetailsBinding
import com.iboism.gpxrecorder.model.GpxContent
import com.iboism.gpxrecorder.recording.configurator.RecordingConfiguratorView
import com.iboism.gpxrecorder.records.details.MapController
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.realm.Realm
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import java.util.concurrent.TimeUnit

class RecorderFragment : Fragment(), RecorderServiceConnection.OnServiceConnectedDelegate {
    private var gpxId: Long? = null
    private var mapController: MapController? = null
    private var serviceConnection: RecorderServiceConnection = RecorderServiceConnection(this)
    private var observableInterval: Observable<Long> = Observable.interval(5, TimeUnit.SECONDS).observeOn(AndroidSchedulers.mainThread())
    private var intervalObserver: Disposable? = null
    private lateinit var binding: FragmentActiveRouteDetailsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gpxId = arguments?.getLong(Keys.GpxId)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        binding = FragmentActiveRouteDetailsBinding.inflate(layoutInflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val gpxId = gpxId ?: return

        binding.addWptBtn.setOnClickListener(this::appendTrackPointButtonClicked)
        binding.playpauseBtn.setOnClickListener(this::playPauseButtonClicked)
        binding.stopBtn.setOnClickListener(this::stopButtonClicked)

        val moreMenu = PopupMenu(binding.root.context, binding.moreBtn)
        val mapToggleMenuItem: MenuItem = moreMenu.menu.add(R.string.toggle_map_type)
        val updateIntervalMenuItem: MenuItem = moreMenu.menu.add(R.string.update_recording_interval)

        moreMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem) {
                mapToggleMenuItem -> mapController?.toggleMapType()
                updateIntervalMenuItem -> showUpdateIntervalDialog()
                else -> return@setOnMenuItemClickListener false
            }

            return@setOnMenuItemClickListener true
        }

        binding.moreBtn.setOnClickListener { moreMenu.show() }

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
        context?.let {
            LocationRecorderService.requestStopRecording(it)
        }
    }

    private fun showUpdateIntervalDialog() {
        val context = context ?: return
        val dialogView = LayoutInflater.from(context).inflate(R.layout.config_dialog, null)
        dialogView.findViewById<View>(R.id.config_title_editText).visibility = View.GONE

        val configuratorView = RecordingConfiguratorView(
            dialogView,
            serviceConnection.service?.recordingIntervalMillis ?: return,
            isTitleEditable = false,
            screenTitleTextRes = R.string.update_recording_interval,
            doneButtonTextRes = R.string.update_recording_interval
        )

        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .create()

        configuratorView.doneButton.setOnClickListener {
            serviceConnection.service?.updateRecordingInterval(configuratorView.getIntervalMillis())
            dialog.dismiss()
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
            mapController?.redraw()
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
    }

    override fun onSaveInstanceState(outState: Bundle) {
        mapController?.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    private fun updateUI(gpxIdOrNull: Long?) {
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

        binding.currentRecHeader.text = getString(
            R.string.recording_in_progress
        ) + " · " + resources.getQuantityString(
            R.plurals.point_count,
            trackPointCount,
            trackPointCount
        )
        binding.routeTitleTv.text = routeTitle
        val pauseResumeString = if (isPaused) R.string.resume_recording else R.string.pause_recording
        binding.playpauseBtn.setText(pauseResumeString)
        mapController?.redraw()
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
